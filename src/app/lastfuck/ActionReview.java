package app.lastfuck;

/** One explicit, visible approval per mutating action while autonomy is off. */
final class ActionReview {
    private static final Object LOCK = new Object();
    static volatile String pending = "";
    private static Boolean decision;
    static void await(String description) throws InterruptedException {
        synchronized (LOCK) {
            pending=description; decision=null;
            try {
                while (decision==null) LOCK.wait(1000);
                if (!decision) throw new InterruptedException("Action declined. No further tools were run.");
            } finally { pending=""; decision=null; }
        }
    }
    static void decide(boolean allow) { synchronized (LOCK) { if (!pending.isEmpty()) { decision=allow; LOCK.notifyAll(); } } }
}
