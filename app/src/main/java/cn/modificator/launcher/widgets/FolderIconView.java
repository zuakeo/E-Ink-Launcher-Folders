package cn.modificator.launcher.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹图标：一个方框里画最多 9 个（3×3）小图标。
 * <p>
 * 墨水屏只用纯黑白灰，不依赖动画与阴影：
 * 白底 + 1px 黑边圆角矩形，内部 3×3 等分，图标按左上角开始填充。
 */
public class FolderIconView extends View {

  /** 最多显示的小图标数量（3×3） */
  private static final int MAX_ICONS = 9;
  /** 小图标占单元格的比例 */
  private static final float ICON_SCALE = 0.80f;

  private final List<Drawable> icons = new ArrayList<>();
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF box = new RectF();
  private final Rect cell = new Rect();

  public FolderIconView(Context context) {
    this(context, null);
  }

  public FolderIconView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public FolderIconView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    float density = getResources().getDisplayMetrics().density;
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(Color.WHITE);
    borderPaint.setStyle(Paint.Style.STROKE);
    borderPaint.setColor(Color.BLACK);
    borderPaint.setStrokeWidth(Math.max(2f, density * 1.6f));
  }

  public void setIcons(List<Drawable> list) {
    icons.clear();
    if (list != null) {
      icons.addAll(list);
    }
    invalidate();
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
      width = (int) (getResources().getDisplayMetrics().density * 64);
    }
    setMeasuredDimension(width, width);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    float inset = Math.max(borderPaint.getStrokeWidth(), w * 0.015f);
    float radius = w * 0.18f;
    box.set(inset, inset, w - inset, h - inset);
    canvas.drawRoundRect(box, radius, radius, fillPaint);
    canvas.drawRoundRect(box, radius, radius, borderPaint);

    int count = Math.min(MAX_ICONS, icons.size());
    if (count == 0) return;

    float innerW = box.width();
    float innerH = box.height();
    float cellW = innerW / 3f;
    float cellH = innerH / 3f;

    for (int i = 0; i < count; i++) {
      Drawable d = icons.get(i);
      if (d == null) continue;
      int row = i / 3;
      int col = i % 3;
      float size = Math.min(cellW, cellH) * ICON_SCALE;
      float cx = box.left + col * cellW + cellW / 2f;
      float cy = box.top + row * cellH + cellH / 2f;
      cell.set((int) (cx - size / 2), (int) (cy - size / 2),
          (int) (cx + size / 2), (int) (cy + size / 2));
      d.setBounds(cell);
      d.draw(canvas);
    }
  }
}
