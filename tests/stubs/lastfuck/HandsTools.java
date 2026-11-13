package app.lastfuck;
import org.json.*;
final class HandsTools implements CodingAgent.Tools {
 public String available() { throw new UnsupportedOperationException(); }
 public JSONObject call(String name, JSONObject args) { throw new UnsupportedOperationException(); }
 static String excerpt(String text, int limit) { return text.length()>limit ? text.substring(0,limit)+"\n[truncated]" : text; }
}
