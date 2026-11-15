package app.lastfuck;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Pure-Java inference for klein-agent's KleinLM (klein_tools_fixed/neural/klein_lm.py), no PyTorch.
 * Same math as the PyTorch forward in eval mode: token + position + Klein-feature embeddings, FiLM conditioning,
 * pre-LN causal attention with per-head gains on the Klein dependency-link channels, GELU MLP, tied output head.
 * Weights come from export_klein_weights.py (format KLEINW1). Per-person LoRA adapters are not loaded (Chat does not use them).
 */
final class KleinModel {
    final int vocab, ctx, d, layers, heads, condDim, nf;
    private final Map<String, float[]> w = new HashMap<>();

    KleinModel(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new BufferedInputStream(in));
        byte[] magic = new byte[8];
        data.readFully(magic);
        if (!"KLEINW1\n".equals(new String(magic, StandardCharsets.US_ASCII))) throw new IOException("Not a Klein weights file");
        byte[] lenBytes = new byte[4];
        data.readFully(lenBytes);
        int headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        byte[] headerBytes = new byte[headerLen];
        data.readFully(headerBytes);
        try {
            JSONObject header = new JSONObject(new String(headerBytes, StandardCharsets.UTF_8));
            JSONObject c = header.getJSONObject("config");
            vocab = c.getInt("vocab"); ctx = c.getInt("ctx"); d = c.getInt("d");
            layers = c.getInt("layers"); heads = c.getInt("heads"); condDim = c.getInt("cond_dim");
            if (!c.optBoolean("use_klein", true) || c.optBoolean("kind_only", false) || c.optBoolean("use_burgess", false))
                throw new IOException("This checkpoint uses an ablation/Burgess mode the phone port does not implement yet");
            JSONObject tensors = header.getJSONObject("tensors");
            // Tensors are stored back to back in header order; read them in offset order.
            java.util.List<String> names = new java.util.ArrayList<>();
            final Map<String, Long> offsets = new HashMap<>();
            for (java.util.Iterator<String> it = tensors.keys(); it.hasNext();) {
                String name = it.next();
                names.add(name);
                offsets.put(name, tensors.getJSONArray(name).getLong(1));
            }
            names.sort((a, b) -> Long.compare(offsets.get(a), offsets.get(b)));
            long pos = 0;
            for (String name : names) {
                JSONArray spec = tensors.getJSONArray(name);
                JSONArray shape = spec.getJSONArray(0);
                long offset = spec.getLong(1);
                if (offset != pos) throw new IOException("Unexpected tensor layout at " + name);
                int n = 1;
                for (int i = 0; i < shape.length(); i++) n *= shape.getInt(i);
                byte[] raw = new byte[n * 4];
                data.readFully(raw);
                float[] values = new float[n];
                ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
                w.put(name, values);
                pos += raw.length;
            }
        } catch (org.json.JSONException e) { throw new IOException(e); }
        nf = get("klein.weight").length / d;
    }

    private float[] get(String name) {
        float[] v = w.get(name);
        if (v == null) throw new IllegalStateException("Missing tensor " + name);
        return v;
    }

    /** y[o] = b[o] + sum_i W[o][i] x[i]  (PyTorch Linear layout: weight[out][in]). */
    private static void linear(float[] W, float[] b, double[] x, double[] y, int in, int out) {
        for (int o = 0; o < out; o++) {
            double s = b == null ? 0 : b[o];
            int base = o * in;
            for (int i = 0; i < in; i++) s += W[base + i] * x[i];
            y[o] = s;
        }
    }

    private static void layerNorm(double[] x, float[] g, float[] b, double[] y) {
        double mean = 0, var = 0;
        for (double v : x) mean += v;
        mean /= x.length;
        for (double v : x) var += (v - mean) * (v - mean);
        var /= x.length;
        double inv = 1.0 / Math.sqrt(var + 1e-5);
        for (int i = 0; i < x.length; i++) y[i] = (x[i] - mean) * inv * g[i] + b[i];
    }

    /** Exact-form GELU (PyTorch default): 0.5 x (1 + erf(x / sqrt 2)). */
    static double gelu(double x) { return 0.5 * x * (1 + erf(x / Math.sqrt(2))); }

    /** erf via the complementary error function series/continued fraction (Numerical Recipes erfc, ~1.2e-7 relative). */
    static double erf(double x) {
        double z = Math.abs(x), t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
            + t * (-0.18628806 + t * (0.27886187 + t * (-1.13520398 + t * (1.48851587
            + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? 1 - r : r - 1;
    }

    /**
     * @param ids   token ids, length L (≤ ctx)
     * @param feat  L × nf Klein grammar-class multi-hot (klein_features)
     * @param links (query, key, channel) dependency links, channel 0/1
     * @param cond  conditioning vector (cond_dim) or null
     * @return logits L × vocab
     */
    double[][] forward(int[] ids, double[][] feat, int[][] links, double[] cond) {
        int L = ids.length;
        if (L == 0 || L > ctx) throw new IllegalArgumentException("Sequence length must be 1.." + ctx);
        float[] tok = get("tok.weight"), pos = get("pos.weight"), kw = get("klein.weight"), kb = get("klein.bias");
        double[][] x = new double[L][d];
        for (int t = 0; t < L; t++) {
            int id = ids[t];
            if (id < 0 || id >= vocab) throw new IllegalArgumentException("Token id out of range: " + id);
            double[] f = feat[t];
            for (int j = 0; j < d; j++) {
                double s = tok[id * d + j] + pos[t * d + j] + kb[j];
                for (int k = 0; k < nf; k++) s += kw[j * nf + k] * f[k];
                x[t][j] = s;
            }
        }
        if (cond != null) {
            double[] h = new double[d], gb = new double[2 * d];
            linear(get("film.0.weight"), get("film.0.bias"), cond, h, condDim, d);
            for (int j = 0; j < d; j++) h[j] = Math.tanh(h[j]);
            linear(get("film.2.weight"), get("film.2.bias"), h, gb, d, 2 * d);
            for (int t = 0; t < L; t++) for (int j = 0; j < d; j++) x[t][j] = x[t][j] * (1 + gb[j]) + gb[d + j];
        }
        int dh = d / heads;
        double scale = 1 / Math.sqrt(dh);
        double[] ln = new double[d], qkvRow = new double[3 * d], hidden = new double[4 * d], outRow = new double[d];
        for (int b = 0; b < layers; b++) {
            String p = "blocks." + b + ".";
            float[] gain = get(p + "link_gain");
            double[][] q = new double[L][d], k = new double[L][d], v = new double[L][d];
            for (int t = 0; t < L; t++) {
                layerNorm(x[t], get(p + "ln1.weight"), get(p + "ln1.bias"), ln);
                linear(get(p + "qkv.base.weight"), get(p + "qkv.base.bias"), ln, qkvRow, d, 3 * d);
                System.arraycopy(qkvRow, 0, q[t], 0, d);
                System.arraycopy(qkvRow, d, k[t], 0, d);
                System.arraycopy(qkvRow, 2 * d, v[t], 0, d);
            }
            // Link bias: att[h][i][j] += sum_c links[c][i][j] * gain[c][h]  (links are 0/1, duplicates set once)
            boolean[][][] link = new boolean[2][L][L];
            if (links != null) for (int[] e : links) if (e[0] < L && e[1] < L && e[2] >= 0 && e[2] < 2) link[e[2]][e[0]][e[1]] = true;
            double[][] mixed = new double[L][d];
            double[] att = new double[L];
            for (int h = 0; h < heads; h++) {
                int o = h * dh;
                for (int i = 0; i < L; i++) {
                    double max = Double.NEGATIVE_INFINITY;
                    for (int j = 0; j <= i; j++) {
                        double s = 0;
                        for (int c = 0; c < dh; c++) s += q[i][o + c] * k[j][o + c];
                        s *= scale;
                        for (int c = 0; c < 2; c++) if (link[c][i][j]) s += gain[c * heads + h];
                        att[j] = s;
                        if (s > max) max = s;
                    }
                    double sum = 0;
                    for (int j = 0; j <= i; j++) { att[j] = Math.exp(att[j] - max); sum += att[j]; }
                    for (int j = 0; j <= i; j++) {
                        double a = att[j] / sum;
                        for (int c = 0; c < dh; c++) mixed[i][o + c] += a * v[j][o + c];
                    }
                }
            }
            for (int t = 0; t < L; t++) {
                linear(get(p + "out.base.weight"), get(p + "out.base.bias"), mixed[t], outRow, d, d);
                for (int j = 0; j < d; j++) x[t][j] += outRow[j];
                layerNorm(x[t], get(p + "ln2.weight"), get(p + "ln2.bias"), ln);
                linear(get(p + "mlp1.weight"), get(p + "mlp1.bias"), ln, hidden, d, 4 * d);
                for (int j = 0; j < 4 * d; j++) hidden[j] = gelu(hidden[j]);
                linear(get(p + "mlp2.weight"), get(p + "mlp2.bias"), hidden, outRow, 4 * d, d);
                for (int j = 0; j < d; j++) x[t][j] += outRow[j];
            }
        }
        double[][] logits = new double[L][vocab];
        float[] lnw = get("ln.weight"), lnb = get("ln.bias");
        for (int t = 0; t < L; t++) {
            layerNorm(x[t], lnw, lnb, ln);
            linear(tok, null, ln, logits[t], d, vocab);           // output head is tied to the token embedding
        }
        return logits;
    }
}
