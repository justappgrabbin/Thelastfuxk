package android.net;
public class Uri { public final String value; private Uri(String v) { value=v; } public static Uri parse(String v) { return new Uri(v); } }
