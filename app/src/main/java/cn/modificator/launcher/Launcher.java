package cn.modificator.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import cn.modificator.launcher.ftpservice.FTPReceiver;
import cn.modificator.launcher.ftpservice.FTPService;
import cn.modificator.launcher.model.AdminReceiver;
import cn.modificator.launcher.model.AppDataCenter;
import cn.modificator.launcher.model.AppSortComparator;
import cn.modificator.launcher.model.FolderStore;
import cn.modificator.launcher.model.HomeEntranceService;
import cn.modificator.launcher.model.IconCache;
import cn.modificator.launcher.model.WifiControl;
import cn.modificator.launcher.widgets.AppItemBinder;
import cn.modificator.launcher.widgets.BatteryView;
import cn.modificator.launcher.widgets.EInkLauncherView;
import cn.modificator.launcher.widgets.LauncherAdapter;

/**
 * 主界面 Activity - E-Ink 墨水屏桌面启动器。
 */
public class Launcher extends Activity
    implements AppItemBinder.Callback, EInkLauncherView.OnPageChangeListener,
    SettingFragment.OnSettingChangeListener {

  private static final int REQUEST_DEVICE_ADMIN = 10001;

  // ---- Views ----
  private EInkLauncherView launcherView;
  private TextView pageStatus;
  private BatteryView batteryProgress;
  private TextView batteryStatus;
  private TextView textClock;

  // ---- Data ----
  private AppDataCenter dataCenter;
  private Config config;
  private FolderStore folderStore;
  private Calendar calendar;
  private boolean isChina = true;
  private IconCache iconCache;
  private LauncherAdapter adapter;
  private AppItemBinder binder;
  private boolean isSystemApp = false;

  // ---- Device Admin ----
  private DevicePolicyManager policyManager;

  // ---- Receivers ----
  private FTPReceiver ftpReceiver = new FTPReceiver();
  private boolean batteryRegistered;
  private boolean timeRegistered;
  private boolean usbRegistered;
  private boolean ftpRegistered;

  private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      updateTimeShow();
    }
  };

  private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      handleBatteryChanged(intent);
    }
  };

  private final BroadcastReceiver appChangeReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      iconCache.clearAppCache();
      dataCenter.refreshAppList(binder.isDelete());
    }
  };

  private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
        iconCache.markDirty();
        refreshIcons();
      }
    }
  };

  // =========================================================================
  // Lifecycle
  // =========================================================================

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.launcher_activity);

    config = new Config(this);
    folderStore = new FolderStore(this);
    WifiControl.init(this);
    applyStatusBarVisibility();

    isChina = getResources().getConfiguration().locale.getCountry().equals("CN");

    initViews();
    registerStaticReceivers();
    checkLaunchHomeNotification();
  }

  @Override
  protected void onResume() {
    super.onResume();
    registerDynamicReceivers();
    refreshIcons();
  }

  @Override
  protected void onPause() {
    super.onPause();
    unregisterDynamicReceivers();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    unregisterDynamicReceivers();
    unregisterReceiver(appChangeReceiver);
  }

  // =========================================================================
  // View 初始化
  // =========================================================================

  private void initViews() {
    policyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

    launcherView = findViewById(R.id.mList);
    pageStatus = findViewById(R.id.pageStatus);
    batteryProgress = findViewById(R.id.batteryProgress);
    batteryStatus = findViewById(R.id.batteryStatus);
    textClock = findViewById(R.id.textClock);

    ImageView settingIcon = findViewById(R.id.toSetting);
    settingIcon.setImageDrawable(
        Utils.tintDrawable(getResources().getDrawable(R.drawable.navibar_icon_settings_highlight),
            ColorStateList.valueOf(0xff000000)));

    // 配置 Binder、Adapter、View
    iconCache = new IconCache();
    binder = new AppItemBinder(this);
    binder.setCallback(this);
    binder.setIconCache(iconCache);
    binder.setFolderStore(folderStore);
    binder.setAppNameLines(config.getAppNameLines());
    binder.setHideAppPkg(config.getHideApps());
    adapter = new LauncherAdapter();
    adapter.setBinder(binder);
    adapter.setFontSize(config.getFontSize());
    adapter.setAppNameLines(config.getAppNameLines());
    launcherView.setAdapter(adapter);
    launcherView.setOnPageChangeListener(this);

    // 初始化数据中心
    dataCenter = new AppDataCenter(this);
    dataCenter.setFolderStore(folderStore);
    dataCenter.setSortMode(config.getSortMode());
    dataCenter.setHideApps(config.getHideApps());
    dataCenter.setPageStatus(pageStatus);
    dataCenter.setAdapter(adapter);

    // 一次性配置网格参数，避免多次重建
    launcherView.configure(config.getColNum(), config.getRowNum(), config.isHideDivider());
    dataCenter.setGridSize(config.getColNum(), config.getRowNum());

    // 翻页按钮
    findViewById(R.id.lastPage).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        dataCenter.showLastPage();
      }
    });
    findViewById(R.id.nextPage).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        dataCenter.showNextPage();
      }
    });

    // 设置按钮
    findViewById(R.id.toSetting).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        getFragmentManager().beginTransaction()
            .replace(android.R.id.content, new SettingFragment())
            .addToBackStack(null)
            .commit();
      }
    });

    // 管理完成按钮
    findViewById(R.id.deleteFinish).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        binder.setDelete(false);
        dataCenter.refreshAppList();
        config.setHideApps(dataCenter.getHideApps());
        v.setVisibility(View.GONE);
      }
    });

    // 排序模式：完成按钮
    findViewById(R.id.orderFinish).setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        exitOrderMode();
      }
    });

    // 时间显示
    calendar = Calendar.getInstance();
    updateTimeShow();

    // 检测系统应用
    try {
      isSystemApp = !isUserApp(getPackageManager().getPackageInfo(getPackageName(), 0));
    } catch (PackageManager.NameNotFoundException e) {
      e.printStackTrace();
    }
  }

  // =========================================================================
  // SettingFragment.OnSettingChangeListener 实现
  // =========================================================================

  @Override
  public void onRowNumChanged(int rowNum) {
    launcherView.setRowNum(rowNum);
    dataCenter.setRowNum(rowNum);
  }

  @Override
  public void onColNumChanged(int colNum) {
    launcherView.setColNum(colNum);
    dataCenter.setColNum(colNum);
  }

  @Override
  public void onFontSizeChanged(float size) {
    adapter.setFontSize(size);
  }

  @Override
  public void onAppNameLinesChanged(int lines) {
    adapter.setAppNameLines(lines);
    binder.setAppNameLines(lines);
  }

  @Override
  public void onHideDividerChanged(boolean hide) {
    launcherView.setHideDivider(hide);
  }

  @Override
  public void onShowStatusBarChanged(boolean show) {
    applyStatusBarVisibility();
  }

  @Override
  public void onShowCustomIconChanged(boolean show) {
    iconCache.markDirty();
    refreshIcons();
  }

  @Override
  public void onEnterManageMode() {
    binder.setDelete(true);
    dataCenter.refreshAppList(true);
    findViewById(R.id.deleteFinish).setVisibility(View.VISIBLE);
  }

  @Override
  public void onSortModeChanged(int mode) {
    dataCenter.setSortMode(mode);
    dataCenter.refreshAppList(binder.isDelete());
  }

  // =========================================================================
  // 布局更新
  // =========================================================================

  private void refreshIcons() {
    if (adapter == null || iconCache == null) return;
    iconCache.refreshCustomIcons(getExternalCacheDir() != null, config.isShowCustomIcon());
    adapter.refreshDisplay();
  }

  // =========================================================================
  // AppItemBinder.Callback 实现
  // =========================================================================

  @Override
  public void onItemClick(ResolveInfo info) {
    String pkgName = info.activityInfo.packageName;

    if (AppDataCenter.LOCK_PACKAGE_NAME.equals(pkgName)) {
      lockScreen();
    } else if (AppDataCenter.WIFI_PACKAGE_NAME.equals(pkgName)) {
      WifiControl.onClickWifiItem();
    } else {
      ComponentName comp = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
      Intent intent = new Intent(Intent.ACTION_MAIN);
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      intent.addCategory(Intent.CATEGORY_LAUNCHER);
      intent.setComponent(comp);
      startActivity(intent);
    }
  }

  @Override
  public void onItemLongClick(View anchor, ResolveInfo info) {
    String packageName = info.activityInfo.packageName;

    if (AppDataCenter.LOCK_PACKAGE_NAME.equals(packageName)) {
      showPowerMenu();
    } else if (AppDataCenter.WIFI_PACKAGE_NAME.equals(packageName)) {
      WifiControl.onLongClickWifiItem();
    } else {
      showAppMenu(info, packageName);
    }
  }

  @Override
  public void onFolderClick(View anchor, String folderId) {
    FolderDialog.show(this, folderStore, folderId, new FolderDialog.Host() {
      @Override
      public void onFolderChanged() {
        dataCenter.refreshAppList();
      }
    });
  }

  @Override
  public void onFolderLongClick(View anchor, String folderId) {
    final FolderStore.Folder folder = folderStore.getFolder(folderId);
    if (folder == null) return;
    final String[] actions = new String[]{
        getString(R.string.folder_open),
        getString(R.string.folder_rename),
        getString(R.string.folder_dissolve)
    };
    new AlertDialog.Builder(this)
        .setTitle(folder.name)
        .setItems(actions, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              onFolderClick(null, folderId);
            } else if (which == 1) {
              showRenameFolderDialog(folderId);
            } else {
              confirmDissolveFolder(folderId);
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  @Override
  public void onOrderModeClick(View anchor, ResolveInfo info) {
    String key = AppDataCenter.keyOf(info);
    String selected = binder.getSelectedKey();

    if (selected == null) {
      binder.setSelectedKey(key);
      adapter.refreshDisplay();
      return;
    }
    if (selected.equals(key)) {
      binder.setSelectedKey(null);
      adapter.refreshDisplay();
      return;
    }

    int from = dataCenter.indexOfKey(selected);
    int to = dataCenter.indexOfKey(key);
    if (from < 0 || to < 0) {
      binder.setSelectedKey(key);
      adapter.refreshDisplay();
      return;
    }
    dataCenter.swapItems(from, to);
    // 保持选中，方便连续挪动同一项
    binder.setSelectedKey(key);
    adapter.refreshDisplay();
    int moved = dataCenter.indexOfKey(key);
    if (moved >= 0) dataCenter.showPageOfItem(moved);
  }

  @Override
  public void onItemDeleteClick(ResolveInfo info) {
    Intent deleteIntent = new Intent(Intent.ACTION_DELETE,
        Uri.parse("package:" + info.activityInfo.packageName));
    startActivity(deleteIntent);
  }

  @Override
  public void onItemHideToggle(String packageName, boolean hidden) {
    // 管理模式下的隐藏切换仅更新 UI 状态，"完成" 按钮处理持久化
  }

  // =========================================================================
  // EInkLauncherView.OnPageChangeListener 实现
  // =========================================================================

  @Override
  public void onPageNext() {
    dataCenter.showNextPage();
  }

  @Override
  public void onPagePrev() {
    dataCenter.showLastPage();
  }

  private void showPowerMenu() {
    if (!isSystemApp) return;
    new AlertDialog.Builder(this)
        .setTitle(R.string.power_title)
        .setItems(R.array.power_menu, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              Intent intent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
              intent.putExtra("android.intent.extra.KEY_CONFIRM", false);
              intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              startActivity(intent);
            } else {
              PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
              pm.reboot("重启");
            }
          }
        })
        .setPositiveButton(R.string.dialog_cancel, null)
        .show();
  }

  /**
   * 长按应用弹出的菜单：移入文件夹 / 隐藏 / 卸载。
   */
  private void showAppMenu(final ResolveInfo info, final String packageName) {
    final List<String> actions = new ArrayList<>();
    actions.add(getString(R.string.menu_move_to_folder));

    final boolean hidden = binder.getHideAppPkg().contains(packageName);
    actions.add(getString(hidden ? R.string.dialog_unhide : R.string.dialog_hide));

    boolean canUninstall = false;
    try {
      canUninstall = (getPackageManager().getPackageInfo(packageName, 0).applicationInfo.flags
          & ApplicationInfo.FLAG_SYSTEM) == 0;
    } catch (PackageManager.NameNotFoundException ignored) {
    }
    if (canUninstall) {
      actions.add(getString(R.string.dialog_uninstall));
    }

    new AlertDialog.Builder(this)
        .setIcon(iconCache.getIcon(packageName, info, getPackageManager()))
        .setTitle(iconCache.getLabel(packageName, info, getPackageManager()))
        .setItems(actions.toArray(new String[0]), new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (which == 0) {
              String appKey = FolderStore.appKey(packageName, info.activityInfo.name);
              showMoveToFolderDialog(appKey, String.valueOf(
                  iconCache.getLabel(packageName, info, getPackageManager())));
            } else if (which == 1) {
              Set<String> hideApps = binder.getHideAppPkg();
              if (!hideApps.add(packageName)) {
                hideApps.remove(packageName);
              }
              config.setHideApps(hideApps);
              dataCenter.refreshAppList();
            } else if (which == 2) {
              Intent deleteIntent = new Intent(Intent.ACTION_DELETE,
                  Uri.parse("package:" + packageName));
              startActivity(deleteIntent);
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  // =========================================================================
  // 文件夹操作
  // =========================================================================

  /** 选择已有文件夹或新建一个 */
  private void showMoveToFolderDialog(final String appKey, final String appLabel) {
    final List<FolderStore.Folder> folders = new ArrayList<>(folderStore.getFolders());
    String[] names = new String[folders.size() + 1];
    for (int i = 0; i < folders.size(); i++) {
      names[i] = folders.get(i).name;
    }
    names[folders.size()] = getString(R.string.folder_create_new);

    new AlertDialog.Builder(this)
        .setTitle(R.string.menu_move_to_folder)
        .setItems(names, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (which < folders.size()) {
              moveAppToFolder(folders.get(which).id, appKey);
            } else {
              showCreateFolderDialog(appLabel, appKey);
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  private void showCreateFolderDialog(final String defaultName, final String appKey) {
    final android.widget.EditText input = new android.widget.EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    input.setText(defaultName);
    input.setSelection(input.getText().length());

    new AlertDialog.Builder(this)
        .setTitle(R.string.folder_create_new)
        .setView(input)
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            String name = input.getText().toString().trim();
            if (name.length() == 0) name = defaultName;
            FolderStore.Folder folder = folderStore.createFolder(name);
            folderStore.save();
            if (appKey != null) {
              moveAppToFolder(folder.id, appKey);
            } else {
              dataCenter.refreshAppList();
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  /** 把应用放进文件夹，并让文件夹占据应用原来的位置 */
  private void moveAppToFolder(String folderId, String appKey) {
    dataCenter.materializeOrder();
    List<String> order = new ArrayList<>(folderStore.getOrder());
    int index = order.indexOf(appKey);

    FolderStore.Folder folder = folderStore.getFolder(folderId);
    boolean wasEmpty = folder != null && folder.items.isEmpty();

    folderStore.addItemToFolder(folderId, appKey);
    order.remove(appKey);
    if (wasEmpty) {
      if (index < 0 || index > order.size()) index = order.size();
      order.add(index, FolderStore.folderKey(folderId));
    }
    folderStore.setOrder(order);

    // 位置一旦手动调整，就切到自定义排序，否则会被名字排序冲掉
    ensureManualSort();
    dataCenter.refreshAppList();
  }

  private void showRenameFolderDialog(final String folderId) {
    final FolderStore.Folder folder = folderStore.getFolder(folderId);
    if (folder == null) return;
    final android.widget.EditText input = new android.widget.EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
    input.setText(folder.name);
    input.setSelection(input.getText().length());

    new AlertDialog.Builder(this)
        .setTitle(R.string.folder_rename)
        .setView(input)
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            String name = input.getText().toString().trim();
            if (name.length() > 0) {
              folderStore.renameFolder(folderId, name);
              dataCenter.refreshAppList();
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  private void confirmDissolveFolder(final String folderId) {
    final FolderStore.Folder folder = folderStore.getFolder(folderId);
    if (folder == null) return;
    new AlertDialog.Builder(this)
        .setTitle(R.string.folder_dissolve)
        .setMessage(getString(R.string.folder_dissolve_confirm, folder.name))
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            dataCenter.materializeOrder();
            List<String> released = folderStore.deleteFolder(folderId);
            List<String> order = new ArrayList<>(folderStore.getOrder());
            int insert = order.size();
            order.addAll(released);
            folderStore.setOrder(order);
            ensureManualSort();
            dataCenter.refreshAppList();
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  // =========================================================================
  // 排序模式
  // =========================================================================

  private void ensureManualSort() {
    config.setSortMode(AppSortComparator.SORT_MANUAL);
    dataCenter.setSortMode(AppSortComparator.SORT_MANUAL);
  }

  @Override
  public void onEnterOrderMode() {
    if (binder.isDelete()) {
      binder.setDelete(false);
      findViewById(R.id.deleteFinish).setVisibility(View.GONE);
    }
    ensureManualSort();
    dataCenter.refreshAppList();
    dataCenter.materializeOrder();
    binder.setOrderMode(true);
    binder.setSelectedKey(null);
    findViewById(R.id.orderBar).setVisibility(View.VISIBLE);
    adapter.refreshDisplay();
  }

  private void exitOrderMode() {
    binder.setOrderMode(false);
    binder.setSelectedKey(null);
    findViewById(R.id.orderBar).setVisibility(View.GONE);
    adapter.refreshDisplay();
  }


  // =========================================================================
  // 时间显示
  // =========================================================================

  private void updateTimeShow() {
    if (textClock == null || calendar == null) return;

    boolean is24Hour = DateFormat.is24HourFormat(this);
    calendar.setTimeInMillis(System.currentTimeMillis());

    StringBuilder sb = new StringBuilder("yyyy-MM-dd ");
    if (!is24Hour && isChina) {
      sb.append(Utils.getAMPMCNString(calendar.get(Calendar.HOUR), calendar.get(Calendar.AM_PM)));
    }
    sb.append(is24Hour ? "HH:mm" : "hh:mm");
    if (!is24Hour && !isChina) {
      sb.append(" a");
    }
    sb.append(" EEEE");

    textClock.setText(new SimpleDateFormat(sb.toString(), Locale.getDefault()).format(calendar.getTime()));
  }

  // =========================================================================
  // 电池信息
  // =========================================================================

  private void handleBatteryChanged(Intent intent) {
    int rawLevel = intent.getIntExtra("level", -1);
    int scale = intent.getIntExtra("scale", -1);
    int status = intent.getIntExtra("status", -1);
    int health = intent.getIntExtra("health", -1);

    int level = (rawLevel >= 0 && scale > 0) ? (rawLevel * 100) / scale : -1;
    batteryProgress.setProgress(level);
    batteryStatus.setVisibility(View.VISIBLE);

    if (BatteryManager.BATTERY_HEALTH_OVERHEAT == health) {
      batteryStatus.setText(R.string.battery_heat);
      return;
    }

    switch (status) {
      case BatteryManager.BATTERY_STATUS_UNKNOWN:
        batteryStatus.setText(R.string.battery_unknown);
        break;
      case BatteryManager.BATTERY_STATUS_CHARGING:
        batteryStatus.setText(R.string.battery_charging);
        break;
      case BatteryManager.BATTERY_STATUS_DISCHARGING:
      case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
        if (level < 15) {
          batteryStatus.setText(R.string.battery_low);
        } else {
          batteryStatus.setVisibility(View.GONE);
        }
        break;
      case BatteryManager.BATTERY_STATUS_FULL:
        batteryStatus.setText(R.string.battery_full);
        break;
      default:
        batteryStatus.setText(R.string.battery_wtf);
        break;
    }
  }

  // =========================================================================
  // 广播注册/注销
  // =========================================================================

  /** 注册生命周期不变的静态广播 */
  private void registerStaticReceivers() {
    // 应用安装/卸载广播
    IntentFilter appChangeFilter = new IntentFilter();
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    appChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
    appChangeFilter.addDataScheme("package");
    registerCompatReceiver(appChangeReceiver, appChangeFilter);
  }

  /** 注册跟随 onResume/onPause 的动态广播 */
  private void registerDynamicReceivers() {
    if (!batteryRegistered) {
      registerCompatReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
      batteryRegistered = true;
    }
    if (!timeRegistered) {
      registerCompatReceiver(timeReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
      timeRegistered = true;
    }
    updateTimeShow();
    if (!usbRegistered) {
      registerUsbReceiver();
    }
    if (!ftpRegistered) {
      IntentFilter ftpFilter = new IntentFilter(FTPService.ACTION_START_FTPSERVER);
      ftpFilter.addAction(FTPService.ACTION_STOP_FTPSERVER);
      registerCompatReceiver(ftpReceiver, ftpFilter);
      ftpRegistered = true;
    }
  }

  private void unregisterDynamicReceivers() {
    if (batteryRegistered) {
      unregisterReceiver(batteryReceiver);
      batteryRegistered = false;
    }
    if (timeRegistered) {
      unregisterReceiver(timeReceiver);
      timeRegistered = false;
    }
    if (usbRegistered) {
      unregisterReceiver(usbReceiver);
      usbRegistered = false;
    }
    if (ftpRegistered) {
      unregisterReceiver(ftpReceiver);
      ftpRegistered = false;
    }
  }

  private void registerUsbReceiver() {
    IntentFilter usbFilter = new IntentFilter();
    usbFilter.addAction(Intent.ACTION_UMS_DISCONNECTED);
    usbFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
    usbFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
    usbFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
    usbFilter.addDataScheme("file");
    registerCompatReceiver(usbReceiver, usbFilter);
    usbRegistered = true;
  }

  private void registerCompatReceiver(BroadcastReceiver receiver, IntentFilter filter) {
    Utils.registerReceiverCompat(this, receiver, filter);
  }

  // =========================================================================
  // 按键处理
  // =========================================================================

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_PAGE_UP) {
      dataCenter.showLastPage();
      return true;
    } else if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
      dataCenter.showNextPage();
      return true;
    } else if (keyCode == KeyEvent.KEYCODE_BACK) {
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK && getFragmentManager().getBackStackEntryCount() == 0) {
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public void onBackPressed() {
    if (binder != null && binder.isOrderMode()) {
      exitOrderMode();
      return;
    }
    if (getFragmentManager().getBackStackEntryCount() > 0) {
      super.onBackPressed();
      config.setFontSize(config.getFontSize());
    }
  }

  // =========================================================================
  // 锁屏
  // =========================================================================

  public void lockScreen() {
    try {
      if (policyManager.isAdminActive(new ComponentName(this, AdminReceiver.class))) {
        policyManager.lockNow();
      } else {
        requestDeviceAdmin();
      }
    } catch (Exception e) {
      showDeviceAdminDialog();
    }
  }

  private void requestDeviceAdmin() {
    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, AdminReceiver.class));
    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "E-Ink Launcher 获取锁屏权限");
    startActivity(intent);
  }

  private void showDeviceAdminDialog() {
    new AlertDialog.Builder(this)
        .setTitle(R.string.launch_failed)
        .setMessage(R.string.launch_devicemanager_failed)
        .setPositiveButton(R.string.launch_devicemanager, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            try {
              Intent intent = Intent.parseUri(
                  "intent:#Intent;component=com.android.settings/.DeviceAdminSettings;end",
                  Intent.URI_INTENT_SCHEME);
              intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
              startActivity(intent);
            } catch (Exception e) {
              e.printStackTrace();
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (resultCode == RESULT_OK && requestCode == REQUEST_DEVICE_ADMIN) {
      policyManager.lockNow();
    }
  }

  // =========================================================================
  // 状态栏/系统应用判断/通知栏
  // =========================================================================

  public void applyStatusBarVisibility() {
    int flags = WindowManager.LayoutParams.FLAG_FULLSCREEN;
    if (config.isShowStatusBar()) {
      getWindow().setFlags(flags, flags);
    } else {
      getWindow().clearFlags(flags);
    }
  }

  public boolean isUserApp(PackageInfo pInfo) {
    return (pInfo.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
  }

  private void checkLaunchHomeNotification() {
    if (!TextUtils.equals(Build.DEVICE, "virgo-perf1")) return;
    Intent service = new Intent(this, HomeEntranceService.class);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(service);
    } else {
      startService(service);
    }
  }
}
