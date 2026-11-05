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
 * <p>
 * ⚠️ 只缓存 {@link Bitmap}，每次调用都新建一个 {@link BitmapDrawable}。
 * 因为 {@link Drawable} 自带 bounds 状态：九宫格文件夹是用 Canvas 手工画的，
 * 会给 drawable 设上自己的 bounds；如果同一个 Drawable 实例又被 ImageView 复用，
 * ImageView 会带着那份"别人的" bounds 去画，图标就会错位、大小不对（实测踩过）。
 */
public final class LineIcons {

  /** 图片按 320dpi 解释，和桌面原始尺寸保持一致 */
  private static final int TARGET_DENSITY = 320;

  private static final Map<String, Bitmap> BITMAP_CACHE = new HashMap<>();

  private LineIcons() {
  }

  public static Drawable load(Context context, String packageName, Drawable fallback) {
    if (context == null || packageName == null) return fallback;

    Bitmap bitmap = BITMAP_CACHE.get(packageName);
    if (bitmap == null) {
      if (BITMAP_CACHE.containsKey(packageName)) {
        // 之前查过，确实没有这个资源
        return fallback;
      }
      bitmap = decodeAsset(context, packageName);
      BITMAP_CACHE.put(packageName, bitmap);   // bitmap 可能是 null，用 key 存在表示"查过了"
      if (bitmap == null) return fallback;
    }

    BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
    drawable.setTargetDensity(TARGET_DENSITY);
    return drawable;
  }

  private static Bitmap decodeAsset(Context context, String packageName) {
    InputStream is = null;
    try {
      is = context.getAssets().open("icons/" + packageName + ".png");
      return BitmapFactory.decodeStream(is);
    } catch (Exception e) {
      return null;
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
    BITMAP_CACHE.clear();
  }
}
