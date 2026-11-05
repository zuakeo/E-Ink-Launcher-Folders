package cn.modificator.launcher;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import cn.modificator.launcher.model.FolderStore;

/**
 * 打开文件夹的弹窗：白底、4 列网格，点图标启动应用，长按移出文件夹。
 */
public class FolderDialog {

  /** 宿主回调 */
  public interface Host {
    /** 文件夹内容即将变化：把当前桌面顺序固化下来 */
    void beforeFolderChange();

    /** 桌面需要刷新（文件夹内容变了） */
    void onFolderChanged();
  }

  private static final int COLUMNS = 4;

  public static void show(final Context context, final FolderStore store,
                          final String folderId, final Host host) {
    final FolderStore.Folder folder = store.getFolder(folderId);
    if (folder == null) return;

    final PackageManager pm = context.getPackageManager();
    final Dialog[] holder = new Dialog[1];

    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.WHITE);
    int pad = dp(context, 16);
    root.setPadding(pad, pad, pad, dp(context, 8));

    final TextView title = new TextView(context);
    title.setText(folder.name);
    title.setTextColor(Color.BLACK);
    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
    title.setPadding(0, 0, 0, dp(context, 10));
    root.addView(title);

    ScrollView scroll = new ScrollView(context);
    final GridLayout grid = new GridLayout(context);
    grid.setColumnCount(COLUMNS);
    scroll.addView(grid, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
    root.addView(scroll, scrollLp);

    final Runnable[] fillRef = new Runnable[1];
    final Runnable fillGrid = new Runnable() {
      @Override
      public void run() {
        grid.removeAllViews();
        FolderStore.Folder current = store.getFolder(folderId);
        if (current == null) return;

        int cellWidth = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.8 / COLUMNS);
        if (current.items.isEmpty()) {
          TextView empty = new TextView(context);
          empty.setText("（空文件夹）");
          empty.setTextColor(Color.BLACK);
          empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
          empty.setPadding(dp(context, 8), dp(context, 16), dp(context, 8), dp(context, 16));
          GridLayout.LayoutParams emptyLp = new GridLayout.LayoutParams();
          emptyLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
          emptyLp.columnSpec = GridLayout.spec(0, COLUMNS);
          grid.addView(empty, emptyLp);
          title.setText(current.name);
          return;
        }

        for (final String itemKey : new ArrayList<>(current.items)) {
          final String pkg = splitPkg(itemKey);
          final String activity = splitActivity(itemKey);
          if (pkg == null) continue;

          LinearLayout cell = new LinearLayout(context);
          cell.setOrientation(LinearLayout.VERTICAL);
          cell.setGravity(Gravity.CENTER_HORIZONTAL);
          cell.setPadding(0, dp(context, 8), 0, dp(context, 8));
          GridLayout.LayoutParams cellLp = new GridLayout.LayoutParams();
          cellLp.width = cellWidth;
          cellLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
          cell.setLayoutParams(cellLp);
          cell.setClickable(true);

          ImageView icon = new ImageView(context);
          int iconSize = cellWidth - dp(context, 24);
          icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));
          icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
          Drawable drawable = null;
          try {
            drawable = pm.getApplicationIcon(pkg);
          } catch (Exception ignored) {
          }
          icon.setImageDrawable(LineIcons.load(context, pkg, drawable));
          cell.addView(icon);

          TextView label = new TextView(context);
          label.setTextColor(Color.BLACK);
          label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
          label.setGravity(Gravity.CENTER);
          label.setMaxLines(1);
          try {
            label.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
          } catch (Exception e) {
            label.setText(pkg);
          }
          cell.addView(label);

          cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
              if (activity == null) return;
              Intent intent = new Intent(Intent.ACTION_MAIN);
              intent.addCategory(Intent.CATEGORY_LAUNCHER);
              intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                  | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
              intent.setComponent(new ComponentName(pkg, activity));
              try {
                context.startActivity(intent);
                if (holder[0] != null) holder[0].dismiss();
              } catch (Exception e) {
                Toast.makeText(context, "启动失败：" + pkg, Toast.LENGTH_SHORT).show();
              }
            }
          });

          cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
              confirmRemoveFromFolder(context, store, folderId, itemKey, pkg, host, new Runnable() {
                @Override
                public void run() {
                  if (store.getFolder(folderId) == null) {
                    // 最后一个应用移出后文件夹没了，直接关掉
                    if (holder[0] != null) holder[0].dismiss();
                  } else if (fillRef[0] != null) {
                    fillRef[0].run();
                  }
                  if (host != null) host.onFolderChanged();
                }
              });
              return true;
            }
          });

          grid.addView(cell);
        }
        title.setText(current.name);
      }
    };
    fillRef[0] = fillGrid;
    fillGrid.run();

    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setView(root);
    builder.setNegativeButton(R.string.folder_close, null);
    builder.setNeutralButton(R.string.folder_rename, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        showRenameDialog(context, store, folderId, new Runnable() {
          @Override
          public void run() {
            fillGrid.run();
            if (host != null) host.onFolderChanged();
          }
        });
      }
    });
    builder.setPositiveButton(R.string.folder_dissolve, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        store.deleteFolder(folderId);
        if (host != null) host.onFolderChanged();
      }
    });

    Dialog dialog = builder.create();
    holder[0] = dialog;
    dialog.show();
  }

  // =========================================================================
  // 子操作
  // =========================================================================

  private static void confirmRemoveFromFolder(final Context context, final FolderStore store,
                                              final String folderId, final String itemKey,
                                              String pkg, final Host host,
                                              final Runnable onDone) {
    new AlertDialog.Builder(context)
        .setTitle(R.string.folder_remove_title)
        .setMessage(pkg)
        .setPositiveButton(R.string.folder_remove_confirm, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            if (host != null) host.beforeFolderChange();
            int insertAt = store.getOrder().indexOf(FolderStore.folderKey(folderId));
            store.removeItemFromFolder(folderId, itemKey);
            store.insertOrder(itemKey, insertAt < 0 ? store.getOrder().size() : insertAt + 1);
            onDone.run();
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  private static void showRenameDialog(final Context context, final FolderStore store,
                                       final String folderId, final Runnable onDone) {
    final EditText input = new EditText(context);
    input.setInputType(InputType.TYPE_CLASS_TEXT);
    FolderStore.Folder folder = store.getFolder(folderId);
    if (folder != null) input.setText(folder.name);
    input.setSelection(input.getText().length());

    new AlertDialog.Builder(context)
        .setTitle(R.string.folder_rename)
        .setView(input)
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            String name = input.getText().toString().trim();
            if (name.length() > 0) {
              store.renameFolder(folderId, name);
              onDone.run();
            }
          }
        })
        .setNegativeButton(R.string.dialog_cancel, null)
        .show();
  }

  // =========================================================================
  // 工具
  // =========================================================================

  public static String splitPkg(String key) {
    if (key == null || !key.startsWith(FolderStore.APP_PREFIX)) return null;
    String body = key.substring(FolderStore.APP_PREFIX.length());
    int bar = body.indexOf('|');
    return bar < 0 ? body : body.substring(0, bar);
  }

  public static String splitActivity(String key) {
    if (key == null || !key.startsWith(FolderStore.APP_PREFIX)) return null;
    String body = key.substring(FolderStore.APP_PREFIX.length());
    int bar = body.indexOf('|');
    return bar < 0 ? null : body.substring(bar + 1);
  }

  private static int dp(Context context, int value) {
    return (int) (context.getResources().getDisplayMetrics().density * value);
  }
}
