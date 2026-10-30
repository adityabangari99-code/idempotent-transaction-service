package com.example.transactions;

import java.math.BigDecimal;
import java.util.*;

/** Dependency-free reference implementation; state is deliberately in-memory. */
public final class Main {
    public enum Type { CREDIT, DEBIT }
    public enum Status { RECEIVED, PROCESSING, PROCESSED, DUPLICATE, FAILED, RETRY_PENDING, PENDING }
    public record Request(String transactionId, String requestId, String accountId,
                          Type type, BigDecimal amount, String currency, long sequence) {}
    public record Result(String transactionId, Status status, String detail) {}

    public static final class Processor {
        private final Map<String, BigDecimal> balances = new HashMap<>();
        private final Map<String, Long> nextSequence = new HashMap<>();
        private final Map<String, Request> accepted = new HashMap<>();
        private final Map<String, String> requestOwners = new HashMap<>();
        private final Map<String, Status> statuses = new HashMap<>();
        private final Map<String, TreeMap<Long, Request>> pending = new HashMap<>();
        private final Map<String, Integer> attempts = new HashMap<>();
        private final Map<String, Integer> simulatedFailures = new HashMap<>();
        private final int maxAttempts;

        public Processor(int maxAttempts) {
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
            this.maxAttempts = maxAttempts;
        }
        public synchronized void failNextAttempts(String transactionId, int count) {
            if (count < 0) throw new IllegalArgumentException("count must not be negative");
            simulatedFailures.put(transactionId, count);
        }
        public synchronized Result submit(Request r) {
            String error = validate(r);
            if (error != null) return new Result(r == null ? null : r.transactionId(), Status.FAILED, error);
            String existingOwner = requestOwners.get(r.requestId());
            if (existingOwner != null && !existingOwner.equals(r.transactionId()))
                return new Result(r.transactionId(), Status.FAILED, "requestId belongs to another transaction");
            Request original = accepted.get(r.transactionId());
            if (original != null) {
                if (!sameBusinessPayload(original, r))
                    return new Result(r.transactionId(), Status.FAILED, "conflicting transaction payload");
                return new Result(r.transactionId(), Status.DUPLICATE, "already accepted: " + statuses.get(r.transactionId()));
            }
            long expected = nextSequence.getOrDefault(r.accountId(), 1L);
            if (r.sequence() < expected) return new Result(r.transactionId(), Status.FAILED, "stale sequence");
            Request occupying = pending.getOrDefault(r.accountId(), new TreeMap<>()).get(r.sequence());
            if (occupying != null) return new Result(r.transactionId(), Status.FAILED, "sequence already reserved");
            accepted.put(r.transactionId(), r);
            requestOwners.put(r.requestId(), r.transactionId());
            statuses.put(r.transactionId(), Status.RECEIVED);
            pending.computeIfAbsent(r.accountId(), ignored -> new TreeMap<>()).put(r.sequence(), r);
            drain(r.accountId());
            return new Result(r.transactionId(), statuses.get(r.transactionId()), "accepted");
        }
        public synchronized void retryPending() {
            for (String account : new ArrayList<>(pending.keySet())) drain(account);
        }
        private void drain(String account) {
            TreeMap<Long, Request> queue = pending.get(account);
            if (queue == null) return;
            while (true) {
                long expected = nextSequence.getOrDefault(account, 1L);
                Request r = queue.get(expected);
                if (r == null) break;
                if (!apply(r)) break;
                queue.remove(expected);
                nextSequence.put(account, expected + 1);
            }
            for (Request r : queue.values()) {
                if (statuses.get(r.transactionId()) == Status.RECEIVED)
                    statuses.put(r.transactionId(), Status.PENDING);
            }
        }
        private boolean apply(Request r) {
            String id = r.transactionId();
            if (statuses.get(id) == Status.FAILED) return false;
            statuses.put(id, Status.PROCESSING);
            attempts.merge(id, 1, Integer::sum);
            int remaining = simulatedFailures.getOrDefault(id, 0);
            if (remaining > 0) {
                simulatedFailures.put(id, remaining - 1);
                statuses.put(id, attempts.get(id) >= maxAttempts ? Status.FAILED : Status.RETRY_PENDING);
                return false;
            }
            BigDecimal oldBalance = balances.getOrDefault(r.accountId(), BigDecimal.ZERO);
            BigDecimal updated = r.type() == Type.CREDIT ? oldBalance.add(r.amount()) : oldBalance.subtract(r.amount());
            // One synchronized critical section updates balance and marks transaction processed.
            balances.put(r.accountId(), updated);
            statuses.put(id, Status.PROCESSED);
            return true;
        }
        public synchronized BigDecimal balance(String account) { return balances.getOrDefault(account, BigDecimal.ZERO); }
        public synchronized Status status(String id) { return statuses.get(id); }
        public synchronized int attempts(String id) { return attempts.getOrDefault(id, 0); }
        public synchronized Map<Status, Long> summary() {
            Map<Status, Long> result = new EnumMap<>(Status.class);
            for (Status status : statuses.values()) result.merge(status, 1L, Long::sum);
            return Collections.unmodifiableMap(result);
        }
        public synchronized Map<String, Long> missingSequenceByAccount() {
            Map<String, Long> gaps = new TreeMap<>();
            for (var entry : pending.entrySet()) {
                if (!entry.getValue().isEmpty()) gaps.put(entry.getKey(), nextSequence.getOrDefault(entry.getKey(), 1L));
            }
            return gaps;
        }
        private static boolean sameBusinessPayload(Request a, Request b) {
            return a.accountId().equals(b.accountId()) && a.type() == b.type()
                    && a.amount().compareTo(b.amount()) == 0 && a.currency().equals(b.currency())
                    && a.sequence() == b.sequence();
        }
        private static String validate(Request r) {
            if (r == null) return "null request";
            if (blank(r.transactionId()) || blank(r.requestId()) || blank(r.accountId())) return "missing identifier";
            if (r.type() == null || r.amount() == null || r.amount().signum() <= 0) return "invalid type or amount";
            if (blank(r.currency()) || !r.currency().matches("[A-Z]{3}")) return "invalid currency";
            if (r.sequence() < 1) return "sequence must be >= 1";
            return null;
        }
        private static boolean blank(String s) { return s == null || s.isBlank(); }
    }
    public static void main(String[] args) {
        Processor p = new Processor(3);
        p.failNextAttempts("TXN-2005", 1);
        List<Request> batch = List.of(
            req("TXN-2002", "REQ-2", "ACC-201", Type.DEBIT, "40", 2),
            req("TXN-2001", "REQ-1", "ACC-201", Type.CREDIT, "100", 1),
            req("TXN-2002", "REQ-2-DUP", "ACC-201", Type.DEBIT, "40", 2),
            req("TXN-2003", "REQ-3", "ACC-201", Type.CREDIT, "25", 3),
            req("TXN-2004", "REQ-4", "ACC-202", Type.DEBIT, "-10", 1),
            req("TXN-2005", "REQ-5", "ACC-203", Type.CREDIT, "500", 1));
        for (Request r : batch) System.out.println(p.submit(r));
        p.retryPending();
        System.out.println("Balances: ACC-201=" + p.balance("ACC-201") + ", ACC-203=" + p.balance("ACC-203"));
        System.out.println("Summary: " + p.summary());
        System.out.println("Missing sequences: " + p.missingSequenceByAccount());
    }
    private static Request req(String id, String request, String account, Type type, String amount, long seq) {
        return new Request(id, request, account, type, new BigDecimal(amount), "USD", seq);
    }
}
