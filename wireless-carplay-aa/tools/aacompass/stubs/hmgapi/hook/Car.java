package hmgapi.hook;

public final class Car {
    public static boolean aaGuiding;
    public static boolean cpGuiding;
    public static boolean androidAutoGuiding(android.content.Context c) { return aaGuiding; }
    public static boolean carPlayGuiding(android.content.Context c) { return cpGuiding; }
}
