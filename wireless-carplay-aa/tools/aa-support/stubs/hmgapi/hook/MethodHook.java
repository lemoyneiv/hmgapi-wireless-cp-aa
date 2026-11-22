package hmgapi.hook;

public abstract class MethodHook {
    protected void beforeHookedMethod(Param p) throws Throwable {}
    protected void afterHookedMethod(Param p) throws Throwable {}
    public final boolean skipsOriginal() throws Throwable {
        Param p = new Param();
        beforeHookedMethod(p);
        return p.returnEarly;
    }
    public final Param before(Object target, Object... args) throws Throwable {
        return beforeMethod(null, target, args);
    }
    public final Param beforeMethod(java.lang.reflect.Member m, Object target, Object... args) throws Throwable {
        Param p = param(m, target, args);
        beforeHookedMethod(p);
        return p;
    }
    public final void after(Object target, Object... args) throws Throwable {
        afterMethod(null, target, args);
    }
    public final void afterMethod(java.lang.reflect.Member m, Object target, Object... args) throws Throwable {
        afterHookedMethod(param(m, target, args));
    }
    private static Param param(java.lang.reflect.Member m, Object target, Object[] args) {
        Param p = new Param();
        p.method = m;
        p.thisObject = target;
        p.args = args;
        return p;
    }
    public static final class Param {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args = new Object[0];
        private boolean returnEarly;
        public void setResult(Object result) { returnEarly = true; }
    }
}
