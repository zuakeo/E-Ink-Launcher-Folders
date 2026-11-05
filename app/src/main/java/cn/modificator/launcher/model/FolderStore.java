package cn.modificator.launcher.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件夹与手动排序的持久化存储。
 * <p>
 * 数据以 JSON 形式保存在独立的 SharedPreferences（{@code launcherFolders}）里：
 * <pre>
 * {"folders":[{"id":"f1","name":"工具","items":["p|pkg|activity"]}],
 *  "order":["p|pkg|activity","f|f1"]}
 * </pre>
 * 条目键（key）统一用前缀区分：{@code p|} 开头是应用，{@code f|} 开头是文件夹。
 */
public class FolderStore {

  public static final String APP_PREFIX = "p|";
  public static final String FOLDER_PREFIX = "f|";

  private static final String PREFS_FILE = "launcherFolders";
  private static final String KEY_FOLDERS = "foldersJson";
  private static final String KEY_ORDER = "orderJson";

  /** 一个文件夹：id 稳定不变，name 可重命名，items 为内部应用的条目键。 */
  public static class Folder {
    public final String id;
    public String name;
    public final List<String> items = new ArrayList<>();

    Folder(String id, String name) {
      this.id = id;
      this.name = name;
    }
  }

  private final SharedPreferences prefs;
  private final List<Folder> folders = new ArrayList<>();
  private final List<String> order = new ArrayList<>();
  private int nextId = 1;

  public FolderStore(Context context) {
    this.prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    load();
  }

  // =========================================================================
  // 键工具
  // =========================================================================

  public static String appKey(String packageName, String activityName) {
    return APP_PREFIX + packageName + "|" + activityName;
  }

  public static String folderKey(String folderId) {
    return FOLDER_PREFIX + folderId;
  }

  public static boolean isFolderKey(String key) {
    return key != null && key.startsWith(FOLDER_PREFIX);
  }

  public static String folderIdOfKey(String key) {
    return key == null ? null : key.substring(FOLDER_PREFIX.length());
  }

  // =========================================================================
  // 读写
  // =========================================================================

  private void load() {
    folders.clear();
    order.clear();
    nextId = 1;
    try {
      JSONObject root = new JSONObject(prefs.getString(KEY_FOLDERS, "{}"));
      JSONArray arr = root.optJSONArray("folders");
      if (arr != null) {
        for (int i = 0; i < arr.length(); i++) {
          JSONObject o = arr.getJSONObject(i);
          Folder f = new Folder(o.getString("id"), o.optString("name", "文件夹"));
          JSONArray items = o.optJSONArray("items");
          if (items != null) {
            for (int j = 0; j < items.length(); j++) {
              f.items.add(items.getString(j));
            }
          }
          folders.add(f);
          nextId = Math.max(nextId, parseId(f.id) + 1);
        }
      }
      JSONArray orderArr = new JSONArray(prefs.getString(KEY_ORDER, "[]"));
      for (int i = 0; i < orderArr.length(); i++) {
        order.add(orderArr.getString(i));
      }
    } catch (JSONException ignored) {
    }
  }

  private static int parseId(String id) {
    try {
      return Integer.parseInt(id.substring(1));
    } catch (Exception e) {
      return 0;
    }
  }

  public void save() {
    JSONObject root = new JSONObject();
    JSONArray arr = new JSONArray();
    try {
      for (Folder f : folders) {
        JSONObject o = new JSONObject();
        o.put("id", f.id);
        o.put("name", f.name);
        JSONArray items = new JSONArray();
        for (String s : f.items) items.put(s);
        o.put("items", items);
        arr.put(o);
      }
      root.put("folders", arr);
      JSONArray orderArr = new JSONArray();
      for (String s : order) orderArr.put(s);
      prefs.edit()
          .putString(KEY_FOLDERS, root.toString())
          .putString(KEY_ORDER, orderArr.toString())
          .apply();
    } catch (JSONException ignored) {
    }
  }

  // =========================================================================
  // 文件夹
  // =========================================================================

  public List<Folder> getFolders() {
    return folders;
  }

  public Folder getFolder(String id) {
    for (Folder f : folders) {
      if (f.id.equals(id)) return f;
    }
    return null;
  }

  /** 应用所在文件夹，不在任何文件夹里返回 null */
  public Folder folderOfItem(String key) {
    for (Folder f : folders) {
      if (f.items.contains(key)) return f;
    }
    return null;
  }

  public boolean isItemInAnyFolder(String key) {
    return folderOfItem(key) != null;
  }

  public Folder createFolder(String name) {
    Folder f = new Folder("f" + nextId++, name);
    folders.add(f);
    return f;
  }

  public void renameFolder(String id, String name) {
    Folder f = getFolder(id);
    if (f != null) {
      f.name = name;
      save();
    }
  }

  /** 解散文件夹，内部应用键返回给调用方以便放回桌面 */
  public List<String> deleteFolder(String id) {
    Folder f = getFolder(id);
    List<String> released = new ArrayList<>();
    if (f == null) return released;
    released.addAll(f.items);
    folders.remove(f);
    order.remove(folderKey(id));
    save();
    return released;
  }

  public void addItemToFolder(String folderId, String appKey) {
    Folder f = getFolder(folderId);
    if (f == null) return;
    for (Folder other : folders) {
      other.items.remove(appKey);
    }
    if (!f.items.contains(appKey)) f.items.add(appKey);
    save();
  }

  public void removeItemFromFolder(String folderId, String appKey) {
    Folder f = getFolder(folderId);
    if (f == null) return;
    f.items.remove(appKey);
    // 最后一个应用移出后文件夹自动消失
    if (f.items.isEmpty()) {
      folders.remove(f);
      order.remove(folderKey(f.id));
    }
    save();
  }

  /** 移除已卸载/已隐藏的应用，并丢掉变空的文件夹 */
  public void prune(List<String> validKeys) {
    boolean changed = false;
    for (int i = folders.size() - 1; i >= 0; i--) {
      Folder f = folders.get(i);
      int before = f.items.size();
      for (int j = f.items.size() - 1; j >= 0; j--) {
        if (!validKeys.contains(f.items.get(j))) f.items.remove(j);
      }
      if (f.items.size() != before) changed = true;
      if (f.items.isEmpty()) {
        folders.remove(i);
        order.remove(folderKey(f.id));
        changed = true;
      }
    }
    if (changed) save();
  }

  // =========================================================================
  // 手动顺序
  // =========================================================================

  public List<String> getOrder() {
    return order;
  }

  public void setOrder(List<String> newOrder) {
    order.clear();
    order.addAll(newOrder);
    save();
  }

  /** 把 key 插到 index（按 order 列表位置），用于新建文件夹后就地占位 */
  public void insertOrder(String key, int index) {
    order.remove(key);
    if (index < 0 || index > order.size()) index = order.size();
    order.add(index, key);
    save();
  }
}
