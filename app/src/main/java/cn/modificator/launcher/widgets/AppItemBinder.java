package cn.modificator.launcher.widgets;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.modificator.launcher.LineIcons;
import cn.modificator.launcher.R;
import cn.modificator.launcher.model.AppDataCenter;
import cn.modificator.launcher.model.FolderStore;
import cn.modificator.launcher.model.IconCache;
import cn.modificator.launcher.model.WifiControl;

/**
 * 负责将应用数据绑定到 {@link LauncherAdapter.ItemViewHolder}，
 * 以及处理点击/长按/卸载/隐藏等交互事件。
 * <p>
 * 与 {@link LauncherAdapter} 协作：Adapter 管理 ViewHolder 池和数据列表，
 * Binder 处理"怎样把数据画到 View 上"以及"用户点了之后做什么"。
 * <p>
 * 两类特殊条目：
 * <ul>
 *   <li>文件夹：图标位置画 3×3 小图标矩阵</li>
 *   <li>排序模式：点击图标只做"选中/交换"，不启动应用</li>
 * </ul>
 */
public class AppItemBinder {

  // =========================================================================
  // 回调接口
  // =========================================================================

  /** 宿主实现此接口以响应用户交互。 */
  public interface Callback {
    /** 普通模式下点击应用 */
    void onItemClick(ResolveInfo info);

    /** 长按应用 */
    void onItemLongClick(View anchor, ResolveInfo info);

    /** 管理模式下点击卸载 */
    void onItemDeleteClick(ResolveInfo info);

    /** 管理模式下切换隐藏状态 */
    void onItemHideToggle(String packageName, boolean hidden);

    /** 点击文件夹 */
    void onFolderClick(View anchor, String folderId);

    /** 直接点中文件夹里的某个小图标 */
    void onFolderChildClick(String itemKey);

    /** 长按文件夹 */
    void onFolderLongClick(View anchor, String folderId);

    /** 排序模式下点击图标 */
    void onOrderModeClick(View anchor, ResolveInfo info);
  }

  // =========================================================================
  // 字段
  // =========================================================================

  private final Context context;
  private final PackageManager packageManager;
  private final Set<String> hideAppPkg = new HashSet<>();

  private Callback callback;
  private IconCache iconCache;
  private FolderStore folderStore;
  private boolean isDelete = false;

  private boolean orderMode = false;
  private String selectedKey = null;
  private int appNameLines = Integer.MAX_VALUE;
  private boolean showFolderName = false;

  // 当前绑定的数据（由 Adapter 在 bindAll 时传入）
  private List<AppDataCenter.Placement> dataRef;

  // =========================================================================
  // 构造器
  // =========================================================================

  public AppItemBinder(Context context) {
    this.context = context;
    this.packageManager = context.getPackageManager();
  }

  // =========================================================================
  // 外部依赖
  // =========================================================================

  public void setCallback(Callback callback) {
    this.callback = callback;
  }

  public void setIconCache(IconCache iconCache) {
    this.iconCache = iconCache;
  }

  public void setFolderStore(FolderStore folderStore) {
    this.folderStore = folderStore;
  }

  // =========================================================================
  // 管理模式 / 排序模式
  // =========================================================================

  public void setDelete(boolean delete) {
    this.isDelete = delete;
    if (delete) {
      orderMode = false;
      selectedKey = null;
    }
  }

  public boolean isDelete() {
    return isDelete;
  }

  public void setOrderMode(boolean orderMode) {
    this.orderMode = orderMode;
    if (!orderMode) selectedKey = null;
  }

  public boolean isOrderMode() {
    return orderMode;
  }

  public String getSelectedKey() {
    return selectedKey;
  }

  public void setSelectedKey(String key) {
    this.selectedKey = key;
  }

  // =========================================================================
  // 隐藏应用
  // =========================================================================

  public void setHideAppPkg(Set<String> pkgs) {
    hideAppPkg.clear();
    hideAppPkg.addAll(pkgs);
  }

  public Set<String> getHideAppPkg() {
    return hideAppPkg;
  }

  // =========================================================================
  // 绑定入口（包级可见，由 LauncherAdapter 调用）
  // =========================================================================

  /**
   * 绑定所有 ViewHolder。
   *
   * @param holders ViewHolder 列表
   * @param data    当前页要显示的应用列表
   */
  void bindAll(List<LauncherAdapter.ItemViewHolder> holders, List<AppDataCenter.Placement> data) {
    this.dataRef = data;
    Map<String, File> customIcons = iconCache != null ? iconCache.getCustomIconMap() : null;
    WifiControl.bind(null, customIcons);

    for (int i = 0; i < holders.size(); i++) {
      LauncherAdapter.ItemViewHolder holder = holders.get(i);
      if (i < data.size()) {
        bindItem(holder, i, customIcons);
      } else {
        clearItem(holder);
      }
    }
  }

  /**
   * 更新所有 ViewHolder 的管理模式状态（删除/隐藏按钮可见性）。
   */
  void updateDeleteState(List<LauncherAdapter.ItemViewHolder> holders, List<AppDataCenter.Placement> data) {
    for (int i = 0; i < holders.size() && i < data.size(); i++) {
      LauncherAdapter.ItemViewHolder holder = holders.get(i);
      boolean folder = AppDataCenter.isFolderPackage(data.get(i).info.activityInfo.packageName);

      if (!isDelete) {
        holder.menuContainer.setVisibility(View.GONE);
        continue;
      }

      // 文件夹不参与批量隐藏/卸载，避免误操作
      if (folder) {
        holder.menuContainer.setVisibility(View.GONE);
        continue;
      }

      holder.menuContainer.setVisibility(View.VISIBLE);
      String pkg = data.get(i).info.activityInfo.packageName;

      boolean canDelete = false;
      if (!AppDataCenter.WIFI_PACKAGE_NAME.equals(pkg)
          && !AppDataCenter.LOCK_PACKAGE_NAME.equals(pkg)) {
        try {
          canDelete = (packageManager.getPackageInfo(pkg, 0).applicationInfo.flags
              & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
      }

      holder.menuDelete.setVisibility(canDelete ? View.VISIBLE : View.GONE);
      holder.menuHide.setSelected(hideAppPkg.contains(pkg));
    }
  }

  // =========================================================================
  // 单项绑定
  // =========================================================================

  private void bindItem(LauncherAdapter.ItemViewHolder holder, int position,
                         Map<String, File> customIcons) {
    ResolveInfo info = dataRef.get(position).info;
    String pkg = info.activityInfo.packageName;

    // —— 文件夹 ——
    if (AppDataCenter.isFolderPackage(pkg)) {
      bindFolder(holder, AppDataCenter.folderIdOf(pkg), customIcons);
    } else if (AppDataCenter.WIFI_PACKAGE_NAME.equals(pkg)) {
      holder.folderImage.setVisibility(View.GONE);
      holder.appImage.setVisibility(View.VISIBLE);
      setIconWeight(holder.appImage, 0.5f);
      WifiControl.bind(holder.itemView, customIcons);
      holder.appName.setMaxLines(appNameLines);
    } else if (AppDataCenter.LOCK_PACKAGE_NAME.equals(pkg)) {
      holder.folderImage.setVisibility(View.GONE);
      holder.appImage.setVisibility(View.VISIBLE);
      setIconWeight(holder.appImage, 0.5f);
      loadIcon(holder.appImage, pkg, R.drawable.ic_onekeylock, customIcons);
      holder.appName.setText(R.string.item_lockscreen);
      holder.appName.setMaxLines(appNameLines);
    } else {
      holder.folderImage.setVisibility(View.GONE);
      holder.appImage.setVisibility(View.VISIBLE);
      setIconWeight(holder.appImage, 0.5f);
      loadIcon(holder.appImage, pkg, info, customIcons);
      holder.appName.setText(iconCache != null
          ? iconCache.getLabel(pkg, info, packageManager)
          : info.loadLabel(packageManager));
      holder.appName.setMaxLines(appNameLines);
    }

    // —— 排序模式的高亮 ——
    boolean selected = orderMode
        && selectedKey != null
        && selectedKey.equals(AppDataCenter.keyOf(info));
    holder.selectBox.setVisibility(selected ? View.VISIBLE : View.GONE);

    // —— 监听器（通过 tag 传递 position，复用单例监听器） ——
    holder.itemView.setTag(position);
    holder.itemView.setOnClickListener(clickListener);
    holder.itemView.setOnLongClickListener(longClickListener);
    holder.menuDelete.setTag(position);
    holder.menuDelete.setOnClickListener(deleteClickListener);
    holder.menuHide.setTag(position);
    holder.menuHide.setOnClickListener(hideClickListener);

    holder.itemView.setVisibility(View.VISIBLE);
    holder.itemView.setAlpha(1);
  }

  private void bindFolder(LauncherAdapter.ItemViewHolder holder, final String folderId,
                          Map<String, File> customIcons) {
    FolderStore.Folder folder = folderStore != null ? folderStore.getFolder(folderId) : null;
    holder.appImage.setVisibility(View.GONE);
    holder.folderImage.setVisibility(View.VISIBLE);
    setIconWeight(holder.folderImage, 0f);

    List<Drawable> icons = new ArrayList<>();
    List<String> keys = new ArrayList<>();
    collectFolderChildren(folder, customIcons, icons, keys);
    holder.folderImage.setItems(icons, keys);
    holder.folderImage.setOnFolderPressListener(new FolderIconView.OnFolderPressListener() {
      @Override
      public void onIconPress(String itemKey) {
        if (callback != null) callback.onFolderChildClick(itemKey);
      }

      @Override
      public void onBlankPress() {
        if (callback != null) callback.onFolderClick(null, folderId);
      }
    });

    // 默认不显示名字，靠九宫格辨认；设置里可以打开
    if (showFolderName) {
      holder.appName.setText(folder != null ? folder.name : "");
      holder.appName.setMaxLines(1);
    } else {
      holder.appName.setText("");
      holder.appName.setMaxLines(0);
    }
  }

  /** 取文件夹里前 9 个应用的图标和条目键 */
  private void collectFolderChildren(FolderStore.Folder folder, Map<String, File> customIcons,
                                     List<Drawable> icons, List<String> keys) {
    if (folder == null) return;

    for (String itemKey : folder.items) {
      if (icons.size() >= 9) break;
      String pkg = AppDataCenter.packageOfKey(itemKey);
      if (pkg == null) continue;
      if (hideAppPkg.contains(pkg)) continue;

      File custom = customIcons != null ? customIcons.get(pkg) : null;
      Drawable icon = null;
      if (custom != null) {
        icon = Drawable.createFromPath(custom.getAbsolutePath());
      }
      if (icon == null) {
        try {
          icon = LineIcons.load(context, pkg, packageManager.getApplicationIcon(pkg));
        } catch (Exception ignored) {
        }
      }
      if (icon == null) continue;
      icons.add(icon);
      keys.add(itemKey);
    }
  }

  public void setAppNameLines(int lines) {
    this.appNameLines = lines;
  }

  public void setShowFolderName(boolean show) {
    this.showFolderName = show;
  }

  /** 调整图标在行内的宽度权重：文件夹占满整格宽，普通图标保持 0.5 的比例 */
  private void setIconWeight(View icon, float weight) {
    if (icon == null) return;
    ViewGroup.LayoutParams params = icon.getLayoutParams();
    if (params instanceof LinearLayout.LayoutParams) {
      LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
      if (lp.weight == weight) return;
      lp.weight = weight;
      icon.setLayoutParams(lp);
      icon.requestLayout();
    }
  }

  private void clearItem(LauncherAdapter.ItemViewHolder holder) {
    holder.appName.setText("");
    holder.appImage.setImageDrawable(null);
    holder.folderImage.setItems(null, null);
    holder.folderImage.setVisibility(View.GONE);
    holder.selectBox.setVisibility(View.GONE);
    holder.itemView.setOnClickListener(null);
    holder.itemView.setOnLongClickListener(null);
    holder.menuDelete.setOnClickListener(null);
    holder.menuHide.setOnClickListener(null);
    holder.itemView.setAlpha(0);
  }

  // =========================================================================
  // 图标加载
  // =========================================================================

  private void loadIcon(ImageView iv, String pkg, int defaultRes,
                         Map<String, File> customIcons) {
    File custom = customIcons != null ? customIcons.get(pkg) : null;
    if (custom != null) {
      iv.setImageURI(Uri.fromFile(custom));
    } else {
      iv.setImageResource(defaultRes);
    }
  }

  private void loadIcon(ImageView iv, String pkg, ResolveInfo info,
                         Map<String, File> customIcons) {
    File custom = customIcons != null ? customIcons.get(pkg) : null;
    if (custom != null) {
      iv.setImageURI(Uri.fromFile(custom));
    } else {
      Drawable icon = iconCache != null
          ? iconCache.getIcon(pkg, info, packageManager)
          : info.loadIcon(packageManager);
      iv.setImageDrawable(LineIcons.load(context, pkg, icon));
    }
  }

  // =========================================================================
  // 点击监听器（单例 + tag 传递位置）
  // =========================================================================

  private final View.OnClickListener clickListener = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      if (callback == null || dataRef == null) return;
      int pos = (int) v.getTag();
      if (pos >= dataRef.size()) return;
      ResolveInfo info = dataRef.get(pos).info;
      String pkg = info.activityInfo.packageName;

      if (isDelete) {
        if (!AppDataCenter.isFolderPackage(pkg)) callback.onItemDeleteClick(info);
        return;
      }
      // 排序模式下文件夹也参与选中/交换，不打开
      if (orderMode) {
        callback.onOrderModeClick(v, info);
        return;
      }
      if (AppDataCenter.isFolderPackage(pkg)) {
        callback.onFolderClick(v, AppDataCenter.folderIdOf(pkg));
        return;
      }
      callback.onItemClick(info);
    }
  };

  private final View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
    @Override
    public boolean onLongClick(View v) {
      if (callback == null || dataRef == null) return false;
      int pos = (int) v.getTag();
      if (pos >= dataRef.size()) return false;
      ResolveInfo info = dataRef.get(pos).info;
      String pkg = info.activityInfo.packageName;

      if (orderMode) {
        callback.onOrderModeClick(v, info);
        return true;
      }
      if (AppDataCenter.isFolderPackage(pkg)) {
        callback.onFolderLongClick(v, AppDataCenter.folderIdOf(pkg));
        return true;
      }
      callback.onItemLongClick(v, info);
      return true;
    }
  };

  private final View.OnClickListener deleteClickListener = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      if (callback == null || dataRef == null) return;
      int pos = (int) v.getTag();
      if (pos >= dataRef.size()) return;
      callback.onItemDeleteClick(dataRef.get(pos).info);
    }
  };

  private final View.OnClickListener hideClickListener = new View.OnClickListener() {
    @Override
    public void onClick(View v) {
      if (dataRef == null) return;
      int pos = (int) v.getTag();
      if (pos >= dataRef.size()) return;
      String pkg = dataRef.get(pos).info.activityInfo.packageName;
      boolean hidden;
      if (hideAppPkg.contains(pkg)) {
        hideAppPkg.remove(pkg);
        hidden = false;
      } else {
        hideAppPkg.add(pkg);
        hidden = true;
      }
      v.setSelected(hidden);
      if (callback != null) {
        callback.onItemHideToggle(pkg, hidden);
      }
    }
  };
}
