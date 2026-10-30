package com.example.transactions;

import java.math.BigDecimal;
import com.example.transactions.Main.*;

public final class ProcessorTest {
    private static Request req(String id, String requestId, String account, Type type, String amount, long seq) {
        return new Request(id, requestId, account, type, new BigDecimal(amount), "USD", seq);
    }
    private static void check(boolean ok, String description) {
        if (!ok) throw new AssertionError(description);
    }
    public static void main(String[] args) {
        Processor p = new Processor(3);
        Request second = req("t2", "r2", "a", Type.DEBIT, "40", 2);
        check(p.submit(second).status() == Status.PENDING, "out-of-order pending");
        check(p.balance("a").signum() == 0, "no early debit");
        check(p.submit(req("t1", "r1", "a", Type.CREDIT, "100", 1)).status() == Status.PROCESSED, "first processed");
        check(p.status("t2") == Status.PROCESSED, "buffer drained");
        check(p.balance("a").compareTo(new BigDecimal("60")) == 0, "correct balance");
        check(p.submit(req("t2", "r2-new", "a", Type.DEBIT, "40", 2)).status() == Status.DUPLICATE, "duplicate transaction");
        check(p.submit(second).status() == Status.DUPLICATE, "replayed request");
        check(p.balance("a").compareTo(new BigDecimal("60")) == 0, "no duplicate effect");
        check(p.submit(req("t2", "rX", "a", Type.DEBIT, "41", 2)).status() == Status.FAILED, "conflicting payload");
        check(p.submit(req("t3", "r1", "a", Type.CREDIT, "1", 3)).status() == Status.FAILED, "requestId collision");
        check(p.submit(req("bad", "badreq", "a", Type.CREDIT, "-1", 3)).status() == Status.FAILED, "invalid isolated");
        check(p.submit(req("t3", "r3", "a", Type.CREDIT, "5", 3)).status() == Status.PROCESSED, "batch continues");
        Processor retry = new Processor(3);
        retry.failNextAttempts("f", 2);
        check(retry.submit(req("f", "rf", "b", Type.CREDIT, "10", 1)).status() == Status.RETRY_PENDING, "retry pending");
        retry.retryPending();
        check(retry.status("f") == Status.RETRY_PENDING, "second retry pending");
        retry.retryPending();
        check(retry.status("f") == Status.PROCESSED && retry.attempts("f") == 3, "retry success");
        check(retry.balance("b").compareTo(new BigDecimal("10")) == 0, "single effect after retry");
        Processor exhausted = new Processor(2);
        exhausted.failNextAttempts("x", 5);
        exhausted.submit(req("x", "rx", "c", Type.CREDIT, "10", 1));
        exhausted.retryPending();
        exhausted.retryPending();
        check(exhausted.status("x") == Status.FAILED && exhausted.attempts("x") == 2, "bounded retry");
        check(exhausted.balance("c").signum() == 0, "no effect after exhaustion");
        Processor gaps = new Processor(2);
        gaps.submit(req("late", "lateReq", "d", Type.CREDIT, "2", 4));
        check(gaps.missingSequenceByAccount().get("d") == 1, "missing sequence reported");
        System.out.println("ALL TESTS PASSED");
    }
}
