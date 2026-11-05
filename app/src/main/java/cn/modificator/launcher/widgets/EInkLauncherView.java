package cn.modificator.launcher.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;

import java.util.List;

import cn.modificator.launcher.R;
import cn.modificator.launcher.model.AppDataCenter;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.makeMeasureSpec;

/**
 * E-Ink 桌面网格布局 ViewGroup。
 * <p>
 * 支持"跨格"条目：普通应用 1×1，文件夹 2×2（位置由 {@link AppDataCenter.Placement} 给出）。
 * 职责仅限于：网格布局（含跨度）、手势翻页、向 Adapter 要 ViewHolder。
 */
public class EInkLauncherView extends ViewGroup {

  // =========================================================================
  // 翻页监听
  // =========================================================================

  /** 翻页手势回调 */
  public interface OnPageChangeListener {
    void onPageNext();
    void onPagePrev();
  }

  // =========================================================================
  // 字段
  // =========================================================================

  private int rowNum = 5;
  private int colNum = 5;
  private boolean hideDivider = false;

  private LauncherAdapter adapter;
  private OnPageChangeListener pageChangeListener;

  private float touchDownX;
  private float touchDownY;
  private float swipeThreshold;

  // =========================================================================
  // 构造器
  // =========================================================================

  public EInkLauncherView(Context context) {
    this(context, null);
  }

  public EInkLauncherView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public EInkLauncherView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  // =========================================================================
  // Adapter & Listener
  // =========================================================================

  /** 设置数据适配器，触发网格重建 */
  public void setAdapter(LauncherAdapter adapter) {
    if (this.adapter != null) {
      this.adapter.detachView();
    }
    this.adapter = adapter;
    if (adapter != null) {
      adapter.attachView(this);
    }
    rebuildGrid();
  }

  public LauncherAdapter getAdapter() {
    return adapter;
  }

  /** 设置翻页手势监听 */
  public void setOnPageChangeListener(OnPageChangeListener listener) {
    this.pageChangeListener = listener;
  }

  // =========================================================================
  // 网格配置
  // =========================================================================

  public void configure(int colNum, int rowNum, boolean hideDivider) {
    this.colNum = colNum;
    this.rowNum = rowNum;
    this.hideDivider = hideDivider;
    rebuildGrid();
  }

  public void setGridSize(int colNum, int rowNum) {
    this.colNum = colNum;
    this.rowNum = rowNum;
    rebuildGrid();
  }

  public void setColNum(int colNum) {
    this.colNum = colNum;
    rebuildGrid();
  }

  public void setRowNum(int rowNum) {
    this.rowNum = rowNum;
    rebuildGrid();
  }

  public void setHideDivider(boolean hideDivider) {
    this.hideDivider = hideDivider;
    rebuildGrid();
  }

  // =========================================================================
  // onLayout / onMeasure
  // =========================================================================

  @Override
  protected void onLayout(boolean changed, int l, int t, int r, int b) {
    int w = getAdjustedWidth();
    int h = getAdjustedHeight();
    if (w <= 0 || h <= 0) return;

    swipeThreshold = Math.min(w, h) / 6f;
    int cellW = w / colNum;
    int cellH = h / rowNum;

    List<AppDataCenter.Placement> placements = getPlacements();
    for (int i = 0; i < getChildCount(); i++) {
      int spanW = 1;
      int spanH = 1;
      int col = i % colNum;
      int row = i / colNum;
      if (placements != null && i < placements.size()) {
        AppDataCenter.Placement p = placements.get(i);
        spanW = p.spanW;
        spanH = p.spanH;
        col = p.col;
        row = p.row;
      }
      getChildAt(i).layout(
          col * cellW, row * cellH,
          (col + spanW) * cellW, (row + spanH) * cellH);
    }
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    int w = getAdjustedWidth();
    int h = getAdjustedHeight();
    if (w <= 0 || h <= 0) return;

    int cellW = w / colNum;
    int cellH = h / rowNum;

    List<AppDataCenter.Placement> placements = getPlacements();
    for (int i = 0; i < getChildCount(); i++) {
      int spanW = 1;
      int spanH = 1;
      if (placements != null && i < placements.size()) {
        AppDataCenter.Placement p = placements.get(i);
        spanW = p.spanW;
        spanH = p.spanH;
      }
      getChildAt(i).measure(
          makeMeasureSpec(cellW * spanW, EXACTLY),
          makeMeasureSpec(cellH * spanH, EXACTLY));
    }
  }

  private List<AppDataCenter.Placement> getPlacements() {
    return adapter == null ? null : adapter.getPlacements();
  }

  private int getAdjustedWidth() {
    return getWidth() - getPaddingLeft() - getPaddingRight();
  }

  private int getAdjustedHeight() {
    return getHeight() - getPaddingTop() - getPaddingBottom();
  }

  // =========================================================================
  // 网格构建
  // =========================================================================

  /** Adapter 换页后调用：条目数或跨度可能变了 */
  void rebuildIfNeeded() {
    rebuildGrid();
  }

  private void rebuildGrid() {
    if (adapter == null) return;
    List<AppDataCenter.Placement> placements = getPlacements();
    int targetCount = placements == null ? 0 : placements.size();

    if (getChildCount() != targetCount) {
      removeAllViews();
      adapter.clearHolders();
      for (int i = 0; i < targetCount; i++) {
        LauncherAdapter.ItemViewHolder holder = adapter.createViewHolder(this);
        addView(holder.itemView);
      }
    }

    for (int i = 0; i < getChildCount(); i++) {
      AppDataCenter.Placement p = placements == null || i >= placements.size()
          ? null : placements.get(i);
      getChildAt(i).setBackgroundResource(getItemBackground(p));
    }

    requestLayout();
    rebind();
  }

  /** 请求 adapter 重新绑定所有数据 */
  void rebind() {
    if (adapter != null) {
      adapter.bindAll();
    }
  }

  private int getItemBackground(AppDataCenter.Placement p) {
    if (hideDivider || p == null) {
      return R.drawable.app_item_final;
    }
    boolean lastCol = p.col + p.spanW >= colNum;
    boolean lastRow = p.row + p.spanH >= rowNum;
    if (lastCol && lastRow) return R.drawable.app_item_final;
    if (lastCol) return R.drawable.app_item_right;
    if (lastRow) return R.drawable.app_item_bottom;
    return R.drawable.app_item_normal;
  }

  // =========================================================================
  // 手势检测
  // =========================================================================

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        touchDownX = event.getX();
        touchDownY = event.getY();
        break;
      case MotionEvent.ACTION_UP:
        int dir = detectSwipe(event.getX(), event.getY());
        if (dir != 0 && pageChangeListener != null) {
          if (dir > 0) pageChangeListener.onPagePrev();
          else pageChangeListener.onPageNext();
          return true;
        }
        break;
    }
    return super.dispatchTouchEvent(event);
  }

  /**
   * 检测滑动方向。
   *
   * @return 1 = 上一页, -1 = 下一页, 0 = 无有效滑动
   */
  private int detectSwipe(float upX, float upY) {
    if (swipeThreshold <= 0) return 0;
    float dx = upX - touchDownX;
    float dy = upY - touchDownY;
    if (dx > swipeThreshold || dy > swipeThreshold) return 1;
    if (dx < -swipeThreshold || dy < -swipeThreshold) return -1;
    return 0;
  }
}
