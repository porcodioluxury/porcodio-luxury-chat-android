package org.telegram.porcodio;

import android.content.Context;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Catalogo Shopify pubblico di Porcodio Luxury (sola lettura, nessun token admin):
 * https://porcodioluxury.com/products.json?limit=N&page=N. La prima pagina è salvata in cache (filesDir).
 */
public class PcShopApi {
    public static final String BASE = "https://porcodioluxury.com";
    public static final int PAGE_SIZE = 30;
    private static final String CACHE = "pc_shop_page1.json";

    public static class Variant {
        public long id;
        public String title;
        public double price;
        public double compareAt;
        public boolean available;
    }

    public static class Product {
        public long id;
        public String title, handle, vendor, type, description;
        public final ArrayList<String> images = new ArrayList<>();
        public final ArrayList<Variant> variants = new ArrayList<>();

        public String image() {
            return images.isEmpty() ? null : images.get(0);
        }

        public String url() {
            return BASE + "/products/" + handle;
        }

        public double minPrice() {
            double m = Double.MAX_VALUE;
            for (Variant v : variants) {
                m = Math.min(m, v.price);
            }
            return m == Double.MAX_VALUE ? 0 : m;
        }

        public double maxPrice() {
            double m = 0;
            for (Variant v : variants) {
                m = Math.max(m, v.price);
            }
            return m;
        }

        public boolean available() {
            for (Variant v : variants) {
                if (v.available) {
                    return true;
                }
            }
            return false;
        }

        public String priceLabel() {
            double a = minPrice(), b = maxPrice();
            return a == b ? money(a) : money(a) + " - " + money(b);
        }
    }

    public interface Callback {
        void onResult(ArrayList<Product> products, boolean fromCache, boolean hasMore, String error);
    }

    public static String money(double v) {
        return String.format(Locale.ITALY, "%.2f", v).replace(",00", "") + " \u20ac";
    }

    /** Pagina 1: prima la cache (fromCache=true), poi la rete. Pagine successive: solo rete. */
    public static void load(int page, Callback cb) {
        if (page == 1) {
            final String cached = readCache();
            if (cached != null) {
                try {
                    ArrayList<Product> list = parse(cached);
                    if (!list.isEmpty()) {
                        AndroidUtilities.runOnUIThread(() -> cb.onResult(list, true, true, null));
                    }
                } catch (Exception ignore) {
                }
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(BASE + "/products.json?limit=" + PAGE_SIZE + "&page=" + page);
                c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Accept", "application/json");
                if (c.getResponseCode() != 200) {
                    throw new Exception("HTTP " + c.getResponseCode());
                }
                InputStream in = c.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                String json = out.toString("UTF-8");
                final ArrayList<Product> list = parse(json);
                if (page == 1) {
                    writeCache(json);
                }
                final boolean more = list.size() >= PAGE_SIZE;
                AndroidUtilities.runOnUIThread(() -> cb.onResult(list, false, more, null));
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                AndroidUtilities.runOnUIThread(() -> cb.onResult(null, false, false, msg));
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        });
    }

    static ArrayList<Product> parse(String json) throws Exception {
        ArrayList<Product> res = new ArrayList<>();
        JSONArray arr = new JSONObject(json).getJSONArray("products");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Product p = new Product();
            p.id = o.optLong("id");
            p.title = o.optString("title");
            p.handle = o.optString("handle");
            p.vendor = o.optString("vendor");
            p.type = o.optString("product_type");
            String body = o.optString("body_html");
            p.description = body.isEmpty() ? "" : Html.fromHtml(body, Html.FROM_HTML_MODE_COMPACT).toString().trim();
            JSONArray imgs = o.optJSONArray("images");
            if (imgs != null) {
                for (int j = 0; j < imgs.length(); j++) {
                    String src = imgs.getJSONObject(j).optString("src");
                    if (!src.isEmpty()) {
                        p.images.add(src);
                    }
                }
            }
            JSONArray vs = o.optJSONArray("variants");
            if (vs != null) {
                for (int j = 0; j < vs.length(); j++) {
                    JSONObject vo = vs.getJSONObject(j);
                    Variant v = new Variant();
                    v.id = vo.optLong("id");
                    v.title = vo.optString("title");
                    v.price = parseD(vo.optString("price"));
                    v.compareAt = vo.isNull("compare_at_price") ? 0 : parseD(vo.optString("compare_at_price"));
                    v.available = vo.optBoolean("available");
                    p.variants.add(v);
                }
            }
            if (!p.handle.isEmpty()) {
                res.add(p);
            }
        }
        return res;
    }

    private static double parseD(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static File cacheFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), CACHE);
    }

    private static String readCache() {
        try {
            File f = cacheFile();
            if (!f.exists()) {
                return null;
            }
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(String json) {
        try {
            FileOutputStream out = new FileOutputStream(cacheFile());
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignore) {
        }
    }
}
