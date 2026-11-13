package app.lastfuck;
import java.nio.file.*; import org.json.*;
public class KleinParity { public static void main(String[] a) throws Exception {
  KleinModel m = new KleinModel(Files.newInputStream(Paths.get(a[0])));
  JSONArray cases = new JSONArray(new String(Files.readAllBytes(Paths.get(a[1])), "UTF-8"));
  double worst = 0; int argmaxMismatch = 0, rows = 0;
  for (int c = 0; c < cases.length(); c++) {
    JSONObject k = cases.getJSONObject(c); JSONArray ids = k.getJSONArray("ids"); int L = ids.length();
    int[] id = new int[L]; double[][] f = new double[L][];
    for (int i=0;i<L;i++){ id[i]=ids.getInt(i); JSONArray r=k.getJSONArray("feat").getJSONArray(i); f[i]=new double[r.length()]; for(int j=0;j<r.length();j++) f[i][j]=r.getDouble(j);}
    JSONArray ls = k.getJSONArray("links"); int[][] links = new int[ls.length()][];
    for (int i=0;i<ls.length();i++){ JSONArray e=ls.getJSONArray(i); links[i]=new int[]{e.getInt(0),e.getInt(1),e.getInt(2)}; }
    double[] cond = null; if (!k.isNull("cond")) { JSONArray cc=k.getJSONArray("cond"); cond=new double[cc.length()]; for(int i=0;i<cond.length;i++) cond[i]=cc.getDouble(i); }
    long t0=System.nanoTime(); double[][] out = m.forward(id, f, links, cond); long ms=(System.nanoTime()-t0)/1000000;
    JSONArray ref = k.getJSONArray("logits");
    for (int i=0;i<L;i++){ JSONArray r=ref.getJSONArray(i); int am=0, bm=0; rows++;
      for(int j=0;j<r.length();j++){ worst=Math.max(worst,Math.abs(out[i][j]-r.getDouble(j))); if(out[i][j]>out[i][am])am=j; if(r.getDouble(j)>r.getDouble(bm))bm=j; }
      if(am!=bm) argmaxMismatch++; }
    System.out.println("case "+c+" L="+L+" links="+links.length+" "+ms+"ms");
  }
  System.out.println("max |logit diff| = "+worst+"  argmax mismatches = "+argmaxMismatch+"/"+rows);
  if (worst > 1e-3 || argmaxMismatch > 0) System.exit(1);
}}
