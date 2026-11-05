package cn.modificator.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 线条图标（assets/icons/&lt;包名&gt;.png）优先替换系统图标。
 * <p>
 * 找不到对应资源时返回传入的兜底图标，行为与旧版 smali 补丁一致。
 */
public final class LineIcons {

  /** 图片按 320dpi 解释，和桌面原始尺寸保持一致 */
  private static final int TARGET_DENSITY = 320;

  private static final Map<String, Drawable> CACHE = new HashMap<>();

  private LineIcons() {
  }

  public static Drawable load(Context context, String packageName, Drawable fallback) {
    if (context == null || packageName == null) return fallback;
    Drawable cached = CACHE.get(packageName);
    if (cached != null) return cached;

    InputStream is = null;
    try {
      is = context.getAssets().open("icons/" + packageName + ".png");
      Bitmap bitmap = BitmapFactory.decodeStream(is);
      if (bitmap == null) return fallback;
      BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
      drawable.setTargetDensity(TARGET_DENSITY);
      CACHE.put(packageName, drawable);
      return drawable;
    } catch (Exception e) {
      return fallback;
    } finally {
      if (is != null) {
        try {
          is.close();
        } catch (Exception ignored) {
        }
      }
    }
  }

  public static void clearCache() {
    CACHE.clear();
  }
}
