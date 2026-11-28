package hmgapi.hook;

public abstract class MethodHook {
    protected void beforeHookedMethod(Param p) throws Throwable {}
    protected void afterHookedMethod(Param p) throws Throwable {}

    public final Param before(Object target, Object... args) throws Throwable {
        Param p = new Param();
        p.thisObject = target;
        p.args = args;
        beforeHookedMethod(p);
        return p;
    }

    public final void afterResult(Object target, Object result, Object... args) throws Throwable {
        Param p = new Param();
        p.thisObject = target;
        p.args = args;
        p.result = result;
        afterHookedMethod(p);
    }

    public static final class Param {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args = new Object[0];
        private Object result;
        private boolean returnEarly;

        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; this.returnEarly = true; }
        public boolean isReturnEarly() { return returnEarly; }
    }
}
