package cn.modificator.launcher.model;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.modificator.launcher.R;
import cn.modificator.launcher.widgets.AppItemBinder;
import cn.modificator.launcher.widgets.LauncherAdapter;

/**
 * 应用数据管理中心，负责加载应用列表（含文件夹）、分页与排版。
 * <p>
 * 网格支持"跨格"条目：普通应用占 1×1，文件夹占 2×2。
 * 每页按顺序做一次简单的首次适配（first-fit）装箱，得到每个条目的列/行/跨度。
 */
public class AppDataCenter {

  /** 虚拟包名：Wifi 控制入口 */
  public static final String WIFI_PACKAGE_NAME = "E-ink_Launcher.WiFi";
  /** 虚拟包名：一键锁屏入口 */
  public static final String LOCK_PACKAGE_NAME = "E-ink_Launcher.Lock";
  /** 文件夹条目的包名前缀，后面接文件夹 id */
  public static final String FOLDER_PACKAGE_PREFIX = "E-ink_Launcher.Folder:";

  /** 一个条目在某一页里的位置 */
  public static class Placement {
    public final ResolveInfo info;
    public final int index;
    public final int col;
    public final int row;
    public final int spanW;
    public final int spanH;

    Placement(ResolveInfo info, int index, int col, int row, int spanW, int spanH) {
      this.info = info;
      this.index = index;
      this.col = col;
      this.row = row;
      this.spanW = spanW;
      this.spanH = spanH;
    }
  }

  private final Context mContext;
  private final List<ResolveInfo> mApps = new ArrayList<>();
  private final List<List<Placement>> pages = new ArrayList<>();
  private int pageIndex = 0;
  private int pageCount = 0;
  private int colNum = 5;
  private int rowNum = 5;
  private LauncherAdapter adapter;
  private AppItemBinder binder;
  private TextView pageStatus;
  private final Set<String> hideApps = new HashSet<>();
  private int sortMode = AppSortComparator.SORT_NAME_ASC;
  private FolderStore folderStore;

  public AppDataCenter(Context context) {
    this.mContext = context;
  }

  // =========================================================================
  // Adapter / Binder 绑定
  // =========================================================================

  public void setAdapter(LauncherAdapter adapter) {
    this.adapter = adapter;
    this.binder = adapter.getBinder();
    if (binder != null) {
      binder.setHideAppPkg(hideApps);
    }
    setPageShow();
  }

  public void setPageStatus(TextView pageStatus) {
    this.pageStatus = pageStatus;
    if (pageStatus != null) {
      pageStatus.setText((pageIndex + 1) + "/" + (pageCount + 1));
    }
  }

  public void setFolderStore(FolderStore folderStore) {
    this.folderStore = folderStore;
  }

  public FolderStore getFolderStore() {
    return folderStore;
  }

  // =========================================================================
  // 条目键工具
  // =========================================================================

  public static boolean isFolderPackage(String packageName) {
    return packageName != null && packageName.startsWith(FOLDER_PACKAGE_PREFIX);
  }

  public static String folderIdOf(String packageName) {
    return packageName.substring(FOLDER_PACKAGE_PREFIX.length());
  }

  /** 条目键：文件夹用 f|id，应用用 p|包名|Activity */
  public static String keyOf(ResolveInfo info) {
    String pkg = info.activityInfo.packageName;
    if (isFolderPackage(pkg)) return FolderStore.folderKey(folderIdOf(pkg));
    return FolderStore.appKey(pkg, info.activityInfo.name);
  }

  /** 从条目键里取包名，格式不对返回 null */
  public static String packageOfKey(String key) {
    if (key == null || !key.startsWith(FolderStore.APP_PREFIX)) return null;
    String body = key.substring(FolderStore.APP_PREFIX.length());
    int bar = body.indexOf('|');
    return bar < 0 ? body : body.substring(0, bar);
  }

  /** 从条目键里取 Activity 名，格式不对返回 null */
  public static String activityOfKey(String key) {
    if (key == null || !key.startsWith(FolderStore.APP_PREFIX)) return null;
    String body = key.substring(FolderStore.APP_PREFIX.length());
    int bar = body.indexOf('|');
    return bar < 0 ? null : body.substring(bar + 1);
  }

  // =========================================================================
  // 隐藏应用管理
  // =========================================================================

  public void setHideApps(Set<String> hideApps) {
    this.hideApps.clear();
    this.hideApps.addAll(hideApps);
    loadApps();
  }

  public Set<String> getHideApps() {
    return hideApps;
  }

  // =========================================================================
  // 列数/行数
  // =========================================================================

  public void setColNum(int colNum) {
    this.colNum = colNum;
    packPages();
    setPageShow();
  }

  public void setRowNum(int rowNum) {
    this.rowNum = rowNum;
    packPages();
    setPageShow();
  }

  /** 批量设置行列数，只触发一次分页更新 */
  public void setGridSize(int colNum, int rowNum) {
    this.colNum = colNum;
    this.rowNum = rowNum;
    packPages();
    setPageShow();
  }

  public int getColNum() {
    return colNum;
  }

  public int getRowNum() {
    return rowNum;
  }

  // =========================================================================
  // 排序
  // =========================================================================

  public void setSortMode(int sortMode) {
    this.sortMode = sortMode;
  }

  public int getSortMode() {
    return sortMode;
  }

  public boolean isManualSort() {
    return sortMode == AppSortComparator.SORT_MANUAL;
  }

  // =========================================================================
  // 翻页
  // =========================================================================

  public void showNextPage() {
    if (pageIndex >= pageCount) return;
    pageIndex++;
    setPageShow();
  }

  public void showLastPage() {
    if (pageIndex <= 0) return;
    pageIndex--;
    setPageShow();
  }

  public int getPageIndex() {
    return pageIndex;
  }

  public int getItemCount() {
    return mApps.size();
  }

  public ResolveInfo getItem(int index) {
    if (index < 0 || index >= mApps.size()) return null;
    return mApps.get(index);
  }

  public int indexOfKey(String key) {
    if (key == null) return -1;
    for (int i = 0; i < mApps.size(); i++) {
      if (key.equals(keyOf(mApps.get(i)))) return i;
    }
    return -1;
  }

  /** 让某一项所在页显示出来 */
  public void showPageOfItem(int index) {
    for (int p = 0; p < pages.size(); p++) {
      for (Placement placement : pages.get(p)) {
        if (placement.index == index) {
          pageIndex = p;
          setPageShow();
          return;
        }
      }
    }
  }

  /** 当前页的排版结果（供 View 布局用） */
  public List<Placement> getCurrentPagePlacements() {
    if (pageIndex < 0 || pageIndex >= pages.size()) return new ArrayList<>();
    return pages.get(pageIndex);
  }

  // =========================================================================
  // 手动排序
  // =========================================================================

  /** 把当前展示顺序固化成 order 列表 */
  public void materializeOrder() {
    if (folderStore == null) return;
    List<String> keys = new ArrayList<>();
    for (ResolveInfo info : mApps) {
      keys.add(keyOf(info));
    }
    folderStore.setOrder(keys);
  }

  /** 交换两个条目的位置（按当前列表下标） */
  public void swapItems(int indexA, int indexB) {
    if (folderStore == null) return;
    if (indexA < 0 || indexB < 0 || indexA >= mApps.size() || indexB >= mApps.size()) return;
    if (indexA == indexB) return;

    List<String> keys = new ArrayList<>();
    for (ResolveInfo info : mApps) {
      keys.add(keyOf(info));
    }
    Collections.swap(keys, indexA, indexB);
    folderStore.setOrder(keys);
    refreshAppList(binder != null && binder.isDelete());
  }

  // =========================================================================
  // 刷新
  // =========================================================================

  public void refreshAppList() {
    refreshAppList(false);
  }

  public void refreshAppList(boolean showAll) {
    if (showAll) {
      loadAllApps();
    } else {
      loadApps();
    }
    setPageShow();
  }

  // =========================================================================
  // 内部加载
  // =========================================================================

  private void loadApps() {
    Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

    if (binder != null) {
      hideApps.clear();
      hideApps.addAll(binder.getHideAppPkg());
    }

    List<ResolveInfo> resolved = mContext.getPackageManager().queryIntentActivities(mainIntent, 0);
    mApps.clear();
    List<String> validAppKeys = new ArrayList<>();

    for (ResolveInfo resolveInfo : resolved) {
      String pkg = resolveInfo.activityInfo.packageName;
      if ("cn.modificator.launcher.Launcher".equals(resolveInfo.activityInfo.name)) continue;
      validAppKeys.add(FolderStore.appKey(pkg, resolveInfo.activityInfo.name));
      if (hideApps.contains(pkg)) continue;
      // 已收进文件夹的应用不再单独占格子
      if (folderStore != null
          && folderStore.isItemInAnyFolder(FolderStore.appKey(pkg, resolveInfo.activityInfo.name))) {
        continue;
      }
      mApps.add(resolveInfo);
    }

    // 清理已卸载/已隐藏的应用，顺便丢掉空文件夹
    if (folderStore != null) {
      folderStore.prune(validAppKeys);
      for (FolderStore.Folder folder : folderStore.getFolders()) {
        if (folder.items.isEmpty()) continue;
        mApps.add(createFolderIcon(folder.id, folder.name));
      }
    }

    if (!hideApps.contains(LOCK_PACKAGE_NAME)) {
      mApps.add(createPowerIcon());
    }
    if (!hideApps.contains(WIFI_PACKAGE_NAME)) {
      mApps.add(createWifiIcon());
    }
    sortApps();
    packPages();
  }

  private void loadAllApps() {
    Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
    mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

    mApps.clear();
    mApps.addAll(mContext.getPackageManager().queryIntentActivities(mainIntent, 0));
    if (folderStore != null) {
      for (FolderStore.Folder folder : folderStore.getFolders()) {
        mApps.add(createFolderIcon(folder.id, folder.name));
      }
    }
    mApps.add(createPowerIcon());
    mApps.add(createWifiIcon());
    if (binder != null) {
      binder.setHideAppPkg(hideApps);
    }
    sortApps();
    packPages();
  }

  private void setPageShow() {
    if (adapter == null) return;
    adapter.setPlacements(getCurrentPagePlacements());
    if (pageStatus != null) {
      pageStatus.setText((pageIndex + 1) + "/" + (pageCount + 1));
    }
  }

  // =========================================================================
  // 装箱（支持 1×1 与 2×2）
  // =========================================================================

  private int spanOf(ResolveInfo info) {
    if (!isFolderPackage(info.activityInfo.packageName)) return 1;
    if (colNum < 2 || rowNum < 2) return 1;
    return 2;
  }

  private void packPages() {
    pages.clear();
    pageCount = 0;
    if (colNum <= 0 || rowNum <= 0) return;

    List<Placement> current = new ArrayList<>();
    boolean[][] used = new boolean[colNum][rowNum];

    for (int i = 0; i < mApps.size(); i++) {
      ResolveInfo info = mApps.get(i);
      int span = spanOf(info);
      int[] pos = findSlot(used, span);
      if (pos == null) {
        pages.add(current);
        current = new ArrayList<>();
        used = new boolean[colNum][rowNum];
        pos = findSlot(used, span);
        if (pos == null) {
          // 极端情况（网格太小），退化成 1×1
          span = 1;
          pos = findSlot(used, span);
          if (pos == null) break;
        }
      }
      for (int c = pos[0]; c < pos[0] + span; c++) {
        for (int r = pos[1]; r < pos[1] + span; r++) {
          used[c][r] = true;
        }
      }
      current.add(new Placement(info, i, pos[0], pos[1], span, span));
    }
    pages.add(current);

    pageCount = Math.max(0, pages.size() - 1);
    pageIndex = Math.min(pageIndex, pageCount);
  }

  /** 找第一个能放下 span×span 的位置 */
  private int[] findSlot(boolean[][] used, int span) {
    for (int r = 0; r + span <= rowNum; r++) {
      for (int c = 0; c + span <= colNum; c++) {
        boolean free = true;
        for (int cc = c; cc < c + span && free; cc++) {
          for (int rr = r; rr < r + span; rr++) {
            if (used[cc][rr]) {
              free = false;
              break;
            }
          }
        }
        if (free) return new int[]{c, r};
      }
    }
    return null;
  }

  // =========================================================================
  // 排序实现
  // =========================================================================

  private void sortApps() {
    if (isManualSort() && folderStore != null) {
      final Map<String, Integer> orderIndex = new HashMap<>();
      List<String> order = folderStore.getOrder();
      for (int i = 0; i < order.size(); i++) {
        orderIndex.put(order.get(i), i);
      }
      final AppSortComparator nameComparator =
          new AppSortComparator(mContext, mContext.getPackageManager(), AppSortComparator.SORT_NAME_ASC);
      Collections.sort(mApps, new Comparator<ResolveInfo>() {
        @Override
        public int compare(ResolveInfo a, ResolveInfo b) {
          Integer ia = orderIndex.get(keyOf(a));
          Integer ib = orderIndex.get(keyOf(b));
          int va = ia == null ? Integer.MAX_VALUE : ia;
          int vb = ib == null ? Integer.MAX_VALUE : ib;
          if (va != vb) return va < vb ? -1 : 1;
          return nameComparator.compare(a, b);
        }
      });
    } else {
      Collections.sort(mApps,
          new AppSortComparator(mContext, mContext.getPackageManager(), sortMode));
    }
  }

  // =========================================================================
  // 虚拟图标创建
  // =========================================================================

  private ResolveInfo createFolderIcon(String folderId, String folderName) {
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.nonLocalizedLabel = folderName;
    resolveInfo.activityInfo = new ActivityInfo();
    resolveInfo.activityInfo.packageName = FOLDER_PACKAGE_PREFIX + folderId;
    resolveInfo.activityInfo.name = FOLDER_PACKAGE_PREFIX + folderId;
    return resolveInfo;
  }

  private ResolveInfo createWifiIcon() {
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.icon = R.drawable.wifi_on;
    resolveInfo.activityInfo = new ActivityInfo();
    resolveInfo.activityInfo.packageName = WIFI_PACKAGE_NAME;
    return resolveInfo;
  }

  private ResolveInfo createPowerIcon() {
    ResolveInfo resolveInfo = new ResolveInfo();
    resolveInfo.icon = R.drawable.ic_onekeylock;
    resolveInfo.activityInfo = new ActivityInfo();
    resolveInfo.activityInfo.packageName = LOCK_PACKAGE_NAME;
    return resolveInfo;
  }
}
