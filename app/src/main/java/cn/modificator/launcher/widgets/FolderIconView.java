package cn.modificator.launcher.widgets;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹图标：一个圆角方框里画 3×3 共最多 9 个小图标。
 * <p>
 * 触控规则（和手机桌面一致）：
 * <ul>
 *   <li>点在某个小图标上 → 直接启动那个应用</li>
 *   <li>点在格子空白处（含空格子）→ 展开文件夹</li>
 * </ul>
 */
public class FolderIconView extends View {

  /** 最多显示的小图标数量（3×3） */
  public static final int GRID = 3;
  private static final int MAX_ICONS = GRID * GRID;
  /** 小图标占单元格的比例 */
  private static final float ICON_SCALE = 0.78f;

  public interface OnFolderPressListener {
    /** 点中第 index 个小图标 */
    void onIconPress(String itemKey);

    /** 点在空白处，展开文件夹 */
    void onBlankPress();
  }

  private final List<Drawable> icons = new ArrayList<>();
  private final List<String> keys = new ArrayList<>();
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF box = new RectF();
  private final Rect cell = new Rect();

  private OnFolderPressListener pressListener;

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
    setClickable(true);
  }

  public void setOnFolderPressListener(OnFolderPressListener listener) {
    this.pressListener = listener;
  }

  public void setItems(List<Drawable> drawables, List<String> itemKeys) {
    icons.clear();
    keys.clear();
    if (drawables != null) icons.addAll(drawables);
    if (itemKeys != null) keys.addAll(itemKeys);
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

    computeBox(w, h);

    float radius = box.width() * 0.14f;
    canvas.drawRoundRect(box, radius, radius, fillPaint);
    canvas.drawRoundRect(box, radius, radius, borderPaint);

    int count = Math.min(MAX_ICONS, icons.size());
    if (count == 0) return;

    float cellW = box.width() / GRID;
    float cellH = box.height() / GRID;

    for (int i = 0; i < count; i++) {
      Drawable d = icons.get(i);
      if (d == null) continue;
      int row = i / GRID;
      int col = i % GRID;
      float size = Math.min(cellW, cellH) * ICON_SCALE;
      float cx = box.left + col * cellW + cellW / 2f;
      float cy = box.top + row * cellH + cellH / 2f;
      cell.set((int) (cx - size / 2), (int) (cy - size / 2),
          (int) (cx + size / 2), (int) (cy + size / 2));
      d.setBounds(cell);
      d.draw(canvas);
    }
  }

  private void computeBox(int w, int h) {
    float inset = Math.max(borderPaint.getStrokeWidth(), Math.min(w, h) * 0.05f);
    box.set(inset, inset, w - inset, h - inset);
  }

  // =========================================================================
  // 触控：点到图标就启动，点空白就展开
  // =========================================================================

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      int index = hitIndex(event.getX(), event.getY());
      if (pressListener != null) {
        if (index >= 0 && index < keys.size()) {
          pressListener.onIconPress(keys.get(index));
        } else {
          pressListener.onBlankPress();
        }
      }
      performClick();
      return true;
    }
    // 按下/移动都吃掉，避免父容器抢走事件
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  /**
   * 触摸点落在第几个小图标上，没命中返回 -1。
   * <p>只认图标本身（外扩一点点容差），图标之间的空隙算"空白"，交给展开逻辑。
   */
  private int hitIndex(float x, float y) {
    computeBox(getWidth(), getHeight());
    if (!box.contains(x, y)) return -1;

    float cellW = box.width() / GRID;
    float cellH = box.height() / GRID;
    float iconSize = Math.min(cellW, cellH) * ICON_SCALE;
    float tolerance = Math.min(cellW, cellH) * 0.10f;
    float reach = iconSize / 2f + tolerance;

    int count = Math.min(MAX_ICONS, keys.size());
    for (int i = 0; i < count; i++) {
      int row = i / GRID;
      int col = i % GRID;
      float cx = box.left + col * cellW + cellW / 2f;
      float cy = box.top + row * cellH + cellH / 2f;
      if (Math.abs(x - cx) <= reach && Math.abs(y - cy) <= reach) {
        return i;
      }
    }
    return -1;
  }
}
