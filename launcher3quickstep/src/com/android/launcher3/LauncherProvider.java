/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import static com.android.launcher3.provider.LauncherDbUtils.dropTable;
import static com.android.launcher3.provider.LauncherDbUtils.tableExists;

import android.annotation.TargetApi;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.UserHandle;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.LauncherSettings.Favorites;
import com.android.launcher3.compat.UserManagerCompat;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.logging.FileLog;
import com.android.launcher3.model.DbDowngradeHelper;
import com.android.launcher3.provider.LauncherDbUtils;
import com.android.launcher3.provider.LauncherDbUtils.SQLiteTransaction;
import com.android.launcher3.util.IntArray;
import com.android.launcher3.util.IntSet;
import com.android.launcher3.util.NoLocaleSQLiteHelper;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.Preconditions;
import com.android.launcher3.util.Thunk;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

public class LauncherProvider extends ContentProvider {
    private static final String TAG = "LauncherProvider";
    private static final boolean LOGD = false;

    private static final String DOWNGRADE_SCHEMA_FILE = "downgrade_schema.json";

    /**
     * Represents the schema of the database. Changes in scheme need not be backwards compatible.
     * When increasing the scheme version, ensure that downgrade_schema.json is updated
     */
    public static final int SCHEMA_VERSION = 28;

    // TODO check authority
    public static final String AUTHORITY = "com.android.launcher3.settings";

    static final String EMPTY_DATABASE_CREATED = "EMPTY_DATABASE_CREATED";

    private final ChangeListenerWrapper mListenerWrapper = new ChangeListenerWrapper();
    private Handler mListenerHandler;

    protected DatabaseHelper mOpenHelper;

    /**
     * $ adb shell dumpsys activity provider com.android.launcher3
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        LauncherAppState appState = LauncherAppState.getInstanceNoCreate();
        if (appState == null || !appState.getModel().isModelLoaded()) {
            return;
        }
        appState.getModel().dumpState("", fd, writer, args);
    }

    @Override
    public boolean onCreate() {
        if (FeatureFlags.IS_DOGFOOD_BUILD) {
            Log.d(TAG, "Launcher process started");
        }
        mListenerHandler = new Handler(mListenerWrapper);

        // The content provider exists for the entire duration of the launcher main process and
        // is the first component to get created.
        MainProcessInitializer.initialize(getContext().getApplicationContext());
        return true;
    }

    /**
     * Sets a provider listener.
     */
    public void setLauncherProviderChangeListener(LauncherProviderChangeListener listener) {
        Preconditions.assertUIThread();
        mListenerWrapper.mListener = listener;
    }

    @Override
    public String getType(Uri uri) {
        SqlArguments args = new SqlArguments(uri, null, null);
        if (TextUtils.isEmpty(args.where)) {
            return "vnd.android.cursor.dir/" + args.table;
        } else {
            return "vnd.android.cursor.item/" + args.table;
        }
    }

    /**
     * Overridden in tests
     */
    protected synchronized void createDbIfNotExists() {
        if (mOpenHelper == null) {
            mOpenHelper = new DatabaseHelper(getContext(), mListenerHandler);
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        createDbIfNotExists();

        SqlArguments args = new SqlArguments(uri, selection, selectionArgs);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(args.table);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor result = qb.query(db, projection, args.where, args.args, null, null, sortOrder);
        result.setNotificationUri(getContext().getContentResolver(), uri);

        return result;
    }

    @Thunk static int dbInsertAndCheck(DatabaseHelper helper,
            SQLiteDatabase db, String table, String nullColumnHack, ContentValues values) {
        if (values == null) {
            throw new RuntimeException("Error: attempting to insert null values");
        }
        if (!values.containsKey(LauncherSettings.Favorites._ID)) {
            throw new RuntimeException("Error: attempting to add item without specifying an id");
        }
        helper.checkId(values);
        return (int) db.insert(table, nullColumnHack, values);
    }

    private void reloadLauncherIfExternal() {
        if (Binder.getCallingPid() != Process.myPid()) {
            LauncherAppState app = LauncherAppState.getInstanceNoCreate();
            if (app != null) {
                app.getModel().forceReload();
            }
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri);

        // In very limited cases, we support system|signature permission apps to modify the db.
        if (Binder.getCallingPid() != Process.myPid()) {
            if (!initializeExternalAdd(initialValues)) {
                return null;
            }
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        addModifiedTime(initialValues);
        final int rowId = dbInsertAndCheck(mOpenHelper, db, args.table, null, initialValues);
        if (rowId < 0) return null;
        mOpenHelper.onAddOrDeleteOp(db);

        uri = ContentUris.withAppendedId(uri, rowId);
        notifyListeners();
        reloadLauncherIfExternal();
        return uri;
    }

    private boolean initializeExternalAdd(ContentValues values) {
        // 1. Ensure that externally added items have a valid item id
        int id = mOpenHelper.generateNewItemId();
        values.put(LauncherSettings.Favorites._ID, id);
        return true;
    }

    @Override
    public int bulkInsert(Uri uri, ContentValues[] values) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try (SQLiteTransaction t = new SQLiteTransaction(db)) {
            int numValues = values.length;
            for (int i = 0; i < numValues; i++) {
                addModifiedTime(values[i]);
                if (dbInsertAndCheck(mOpenHelper, db, args.table, null, values[i]) < 0) {
                    return 0;
                }
            }
            mOpenHelper.onAddOrDeleteOp(db);
            t.commit();
        }

        notifyListeners();
        reloadLauncherIfExternal();
        return values.length;
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {
        createDbIfNotExists();
        try (SQLiteTransaction t = new SQLiteTransaction(mOpenHelper.getWritableDatabase())) {
            boolean isAddOrDelete = false;

            final int numOperations = operations.size();
            final ContentProviderResult[] results = new ContentProviderResult[numOperations];
            for (int i = 0; i < numOperations; i++) {
                ContentProviderOperation op = operations.get(i);
                results[i] = op.apply(this, results, i);

                isAddOrDelete |= (op.isInsert() || op.isDelete()) &&
                        results[i].count != null && results[i].count > 0;
            }
            if (isAddOrDelete) {
                mOpenHelper.onAddOrDeleteOp(t.getDb());
            }

            t.commit();
            reloadLauncherIfExternal();
            return results;
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri, selection, selectionArgs);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        int count = db.delete(args.table, args.where, args.args);
        if (count > 0) {
            mOpenHelper.onAddOrDeleteOp(db);
            notifyListeners();
            reloadLauncherIfExternal();
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        createDbIfNotExists();
        SqlArguments args = new SqlArguments(uri, selection, selectionArgs);

        addModifiedTime(values);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = db.update(args.table, values, args.where, args.args);
        if (count > 0) notifyListeners();

        reloadLauncherIfExternal();
        return count;
    }

    @Override
    public Bundle call(String method, final String arg, final Bundle extras) {
        if (Binder.getCallingUid() != Process.myUid()) {
            return null;
        }
        createDbIfNotExists();

        switch (method) {
            case LauncherSettings.Settings.METHOD_CLEAR_EMPTY_DB_FLAG: {
                clearFlagEmptyDbCreated();
                return null;
            }
            case LauncherSettings.Settings.METHOD_WAS_EMPTY_DB_CREATED : {
                Bundle result = new Bundle();
                result.putBoolean(LauncherSettings.Settings.EXTRA_VALUE,
                        Utilities.getPrefs(getContext()).getBoolean(EMPTY_DATABASE_CREATED, false));
                return result;
            }
            case LauncherSettings.Settings.METHOD_NEW_ITEM_ID: {
                Bundle result = new Bundle();
                result.putInt(LauncherSettings.Settings.EXTRA_VALUE, mOpenHelper.generateNewItemId());
                return result;
            }
            case LauncherSettings.Settings.METHOD_NEW_SCREEN_ID: {
                Bundle result = new Bundle();
                result.putInt(LauncherSettings.Settings.EXTRA_VALUE, mOpenHelper.generateNewScreenId());
                return result;
            }
            case LauncherSettings.Settings.METHOD_CREATE_EMPTY_DB: {
                mOpenHelper.createEmptyDB(mOpenHelper.getWritableDatabase());
                return null;
            }
            case LauncherSettings.Settings.METHOD_LOAD_DEFAULT_FAVORITES: {
                return null;
            }
            case LauncherSettings.Settings.METHOD_NEW_TRANSACTION: {
                Bundle result = new Bundle();
                result.putBinder(LauncherSettings.Settings.EXTRA_VALUE,
                        new SQLiteTransaction(mOpenHelper.getWritableDatabase()));
                return result;
            }
            case LauncherSettings.Settings.METHOD_REFRESH_BACKUP_TABLE: {
                mOpenHelper.mBackupTableExists =
                        tableExists(mOpenHelper.getReadableDatabase(), Favorites.BACKUP_TABLE_NAME);
                return null;
            }
        }
        return null;
    }

    /**
     * Overridden in tests
     */
    protected void notifyListeners() {
        mListenerHandler.sendEmptyMessage(ChangeListenerWrapper.MSG_LAUNCHER_PROVIDER_CHANGED);
    }

    @Thunk static void addModifiedTime(ContentValues values) {
        values.put(LauncherSettings.Favorites.MODIFIED, System.currentTimeMillis());
    }

    private void clearFlagEmptyDbCreated() {
        Utilities.getPrefs(getContext()).edit().remove(EMPTY_DATABASE_CREATED).commit();
    }

    /**
     * The class is subclassed in tests to create an in-memory db.
     */
    public static class DatabaseHelper extends NoLocaleSQLiteHelper implements LayoutParserCallback {
        private final Context mContext;
        private int mMaxItemId = -1;
        private int mMaxScreenId = -1;
        private boolean mBackupTableExists;

        DatabaseHelper(Context context, Handler widgetHostResetHandler) {
            this(context, widgetHostResetHandler, LauncherFiles.LAUNCHER_DB);
            // Table creation sometimes fails silently, which leads to a crash loop.
            // This way, we will try to create a table every time after crash, so the device
            // would eventually be able to recover.
            if (!tableExists(getReadableDatabase(), Favorites.TABLE_NAME)) {
                Log.e(TAG, "Tables are missing after onCreate has been called. Trying to recreate");
                // This operation is a no-op if the table already exists.
                addFavoritesTable(getWritableDatabase(), true);
            }
            mBackupTableExists = tableExists(getReadableDatabase(), Favorites.BACKUP_TABLE_NAME);

            initIds();
        }

        /**
         * Constructor used in tests and for restore.
         */
        public DatabaseHelper(
                Context context, Handler widgetHostResetHandler, String tableName) {
            super(context, tableName, SCHEMA_VERSION);
            mContext = context;
        }

        protected void initIds() {
            // In the case where neither onCreate nor onUpgrade gets called, we read the maxId from
            // the DB here
            if (mMaxItemId == -1) {
                mMaxItemId = initializeMaxItemId(getWritableDatabase());
            }
            if (mMaxScreenId == -1) {
                mMaxScreenId = initializeMaxScreenId(getWritableDatabase());
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (LOGD) Log.d(TAG, "creating new launcher database");

            mMaxItemId = 1;
            mMaxScreenId = 0;

            addFavoritesTable(db, false);

            // Fresh and clean launcher DB.
            mMaxItemId = initializeMaxItemId(db);
            onEmptyDbCreated();
        }

        protected void onAddOrDeleteOp(SQLiteDatabase db) {
            if (mBackupTableExists) {
                dropTable(db, Favorites.BACKUP_TABLE_NAME);
                mBackupTableExists = false;
            }
        }

        /**
         * Overriden in tests.
         */
        protected void onEmptyDbCreated() {
            // Set the flag for empty DB
            Utilities.getPrefs(mContext).edit().putBoolean(EMPTY_DATABASE_CREATED, true).commit();
        }

        public long getSerialNumberForUser(UserHandle user) {
            return UserManagerCompat.getInstance(mContext).getSerialNumberForUser(user);
        }

        public long getDefaultUserSerial() {
            return getSerialNumberForUser(Process.myUserHandle());
        }

        private void addFavoritesTable(SQLiteDatabase db, boolean optional) {
            Favorites.addTableToDb(db, getDefaultUserSerial(), optional);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);

            File schemaFile = mContext.getFileStreamPath(DOWNGRADE_SCHEMA_FILE);
            if (!schemaFile.exists()) {
                handleOneTimeDataUpgrade(db);
            }
            DbDowngradeHelper.updateSchemaFile(schemaFile, SCHEMA_VERSION, mContext);
        }

        /**
         * One-time data updated before support of onDowngrade was added. This update is backwards
         * compatible and can safely be run multiple times.
         * Note: No new logic should be added here after release, as the new logic might not get
         * executed on an existing device.
         * TODO: Move this to db upgrade path, once the downgrade path is released.
         */
        protected void handleOneTimeDataUpgrade(SQLiteDatabase db) {
            // Remove "profile extra"
            UserManagerCompat um = UserManagerCompat.getInstance(mContext);
            for (UserHandle user : um.getUserProfiles()) {
                long serial = um.getSerialNumberForUser(user);
                String sql = "update favorites set intent = replace(intent, "
                        + "';l.profile=" + serial + ";', ';') where itemType = 0;";
                db.execSQL(sql);
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (LOGD) Log.d(TAG, "onUpgrade triggered: " + oldVersion);
            switch (oldVersion) {
                // The version cannot be lower that 12, as Launcher3 never supported a lower
                // version of the DB.
                case 12:
                    // No-op
                case 13: {
                    try (SQLiteTransaction t = new SQLiteTransaction(db)) {
                        // Insert new column for holding widget provider name
                        db.execSQL("ALTER TABLE favorites " +
                                "ADD COLUMN appWidgetProvider TEXT;");
                        t.commit();
                    } catch (SQLException ex) {
                        Log.e(TAG, ex.getMessage(), ex);
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 14: {
                    if (!addIntegerColumn(db, Favorites.MODIFIED, 0)) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 15: {
                    if (!addIntegerColumn(db, Favorites.RESTORED, 0)) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 16:
                    // No-op
                case 17:
                    // No-op
                case 18:
                    // No-op
                case 19: {
                    // Add userId column
                    if (!addIntegerColumn(db, Favorites.PROFILE_ID, getDefaultUserSerial())) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 20:
                    if (!updateFolderItemsRank(db, true)) {
                        break;
                    }
                case 21:
                    // No-op
                case 22: {
                    if (!addIntegerColumn(db, Favorites.OPTIONS, 0)) {
                        // Old version remains, which means we wipe old data
                        break;
                    }
                }
                case 23:
                    // No-op
                case 24:
                    // No-op
                case 25:
                    convertShortcutsToLauncherActivities(db);
                case 26:
                    // QSB was moved to the grid. Clear the first row on screen 0.
                    if (FeatureFlags.QSB_ON_FIRST_SCREEN &&
                            !LauncherDbUtils.prepareScreenZeroToHostQsb(mContext, db)) {
                        break;
                    }
                case 27: {
                    // Update the favorites table so that the screen ids are ordered based on
                    // workspace page rank.
                    IntArray finalScreens = LauncherDbUtils.queryIntArray(db, "workspaceScreens",
                            BaseColumns._ID, null, null, "screenRank");
                    int[] original = finalScreens.toArray();
                    Arrays.sort(original);
                    String updatemap = "";
                    for (int i = 0; i < original.length; i++) {
                        if (finalScreens.get(i) != original[i]) {
                            updatemap += String.format(Locale.ENGLISH, " WHEN %1$s=%2$d THEN %3$d",
                                    Favorites.SCREEN, finalScreens.get(i), original[i]);
                        }
                    }
                    if (!TextUtils.isEmpty(updatemap)) {
                        String query = String.format(Locale.ENGLISH,
                                "UPDATE %1$s SET %2$s=CASE %3$s ELSE %2$s END WHERE %4$s = %5$d",
                                Favorites.TABLE_NAME, Favorites.SCREEN, updatemap,
                                Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP);
                        db.execSQL(query);
                    }
                    dropTable(db, "workspaceScreens");
                }
                case 28:
                    // DB Upgraded successfully
                    return;
            }

            // DB was not upgraded
            Log.w(TAG, "Destroying all old data.");
            createEmptyDB(db);
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            try {
                DbDowngradeHelper.parse(mContext.getFileStreamPath(DOWNGRADE_SCHEMA_FILE))
                        .onDowngrade(db, oldVersion, newVersion);
            } catch (Exception e) {
                Log.d(TAG, "Unable to downgrade from: " + oldVersion + " to " + newVersion +
                        ". Wiping databse.", e);
                createEmptyDB(db);
            }
        }

        /**
         * Clears all the data for a fresh start.
         */
        public void createEmptyDB(SQLiteDatabase db) {
            try (SQLiteTransaction t = new SQLiteTransaction(db)) {
                dropTable(db, Favorites.TABLE_NAME);
                dropTable(db, "workspaceScreens");
                onCreate(db);
                t.commit();
            }
        }

        /**
         * Replaces all shortcuts of type {@link Favorites#ITEM_TYPE_SHORTCUT} which have a valid
         * launcher activity target with {@link Favorites#ITEM_TYPE_APPLICATION}.
         */
        @Thunk void convertShortcutsToLauncherActivities(SQLiteDatabase db) {
            try (SQLiteTransaction t = new SQLiteTransaction(db);
                 // Only consider the primary user as other users can't have a shortcut.
                 Cursor c = db.query(Favorites.TABLE_NAME,
                         new String[] { Favorites._ID, Favorites.INTENT},
                         "itemType=" + Favorites.ITEM_TYPE_SHORTCUT +
                                 " AND profileId=" + getDefaultUserSerial(),
                         null, null, null, null);
                 SQLiteStatement updateStmt = db.compileStatement("UPDATE favorites SET itemType="
                         + Favorites.ITEM_TYPE_APPLICATION + " WHERE _id=?")
            ) {
                final int idIndex = c.getColumnIndexOrThrow(Favorites._ID);
                final int intentIndex = c.getColumnIndexOrThrow(Favorites.INTENT);

                while (c.moveToNext()) {
                    String intentDescription = c.getString(intentIndex);
                    Intent intent;
                    try {
                        intent = Intent.parseUri(intentDescription, 0);
                    } catch (URISyntaxException e) {
                        Log.e(TAG, "Unable to parse intent", e);
                        continue;
                    }

                    if (!PackageManagerHelper.isLauncherAppTarget(intent)) {
                        continue;
                    }

                    int id = c.getInt(idIndex);
                    updateStmt.bindLong(1, id);
                    updateStmt.executeUpdateDelete();
                }
                t.commit();
            } catch (SQLException ex) {
                Log.w(TAG, "Error deduping shortcuts", ex);
            }
        }

        @Thunk boolean updateFolderItemsRank(SQLiteDatabase db, boolean addRankColumn) {
            try (SQLiteTransaction t = new SQLiteTransaction(db)) {
                if (addRankColumn) {
                    // Insert new column for holding rank
                    db.execSQL("ALTER TABLE favorites ADD COLUMN rank INTEGER NOT NULL DEFAULT 0;");
                }

                t.commit();
            } catch (SQLException ex) {
                // Old version remains, which means we wipe old data
                Log.e(TAG, ex.getMessage(), ex);
                return false;
            }
            return true;
        }

        private boolean addIntegerColumn(SQLiteDatabase db, String columnName, long defaultValue) {
            try (SQLiteTransaction t = new SQLiteTransaction(db)) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN "
                        + columnName + " INTEGER NOT NULL DEFAULT " + defaultValue + ";");
                t.commit();
            } catch (SQLException ex) {
                Log.e(TAG, ex.getMessage(), ex);
                return false;
            }
            return true;
        }

        // Generates a new ID to use for an object in your database. This method should be only
        // called from the main UI thread. As an exception, we do call it when we call the
        // constructor from the worker thread; however, this doesn't extend until after the
        // constructor is called, and we only pass a reference to LauncherProvider to LauncherApp
        // after that point
        @Override
        public int generateNewItemId() {
            if (mMaxItemId < 0) {
                throw new RuntimeException("Error: max item id was not initialized");
            }
            mMaxItemId += 1;
            return mMaxItemId;
        }

        public void checkId(ContentValues values) {
            int id = values.getAsInteger(Favorites._ID);
            mMaxItemId = Math.max(id, mMaxItemId);

            Integer screen = values.getAsInteger(Favorites.SCREEN);
            Integer container = values.getAsInteger(Favorites.CONTAINER);
            if (screen != null && container != null
                    && container.intValue() == Favorites.CONTAINER_DESKTOP) {
                mMaxScreenId = Math.max(screen, mMaxScreenId);
            }
        }

        private int initializeMaxItemId(SQLiteDatabase db) {
            return getMaxId(db, "SELECT MAX(%1$s) FROM %2$s", Favorites._ID, Favorites.TABLE_NAME);
        }

        // Generates a new ID to use for an workspace screen in your database. This method
        // should be only called from the main UI thread. As an exception, we do call it when we
        // call the constructor from the worker thread; however, this doesn't extend until after the
        // constructor is called, and we only pass a reference to LauncherProvider to LauncherApp
        // after that point
        public int generateNewScreenId() {
            if (mMaxScreenId < 0) {
                throw new RuntimeException("Error: max screen id was not initialized");
            }
            mMaxScreenId += 1;
            return mMaxScreenId;
        }

        private int initializeMaxScreenId(SQLiteDatabase db) {
            return getMaxId(db, "SELECT MAX(%1$s) FROM %2$s WHERE %3$s = %4$d",
                    Favorites.SCREEN, Favorites.TABLE_NAME, Favorites.CONTAINER,
                    Favorites.CONTAINER_DESKTOP);
        }
    }

    /**
     * @return the max _id in the provided table.
     */
    @Thunk static int getMaxId(SQLiteDatabase db, String query, Object... args) {
        int max = (int) DatabaseUtils.longForQuery(db,
                String.format(Locale.ENGLISH, query, args),
                null);
        if (max < 0) {
            throw new RuntimeException("Error: could not query max id");
        }
        return max;
    }

    static class SqlArguments {
        public final String table;
        public final String where;
        public final String[] args;

        SqlArguments(Uri url, String where, String[] args) {
            if (url.getPathSegments().size() == 1) {
                this.table = url.getPathSegments().get(0);
                this.where = where;
                this.args = args;
            } else if (url.getPathSegments().size() != 2) {
                throw new IllegalArgumentException("Invalid URI: " + url);
            } else if (!TextUtils.isEmpty(where)) {
                throw new UnsupportedOperationException("WHERE clause not supported: " + url);
            } else {
                this.table = url.getPathSegments().get(0);
                this.where = "_id=" + ContentUris.parseId(url);
                this.args = null;
            }
        }

        SqlArguments(Uri url) {
            if (url.getPathSegments().size() == 1) {
                table = url.getPathSegments().get(0);
                where = null;
                args = null;
            } else {
                throw new IllegalArgumentException("Invalid URI: " + url);
            }
        }
    }

    private static class ChangeListenerWrapper implements Handler.Callback {

        private static final int MSG_LAUNCHER_PROVIDER_CHANGED = 1;
        private static final int MSG_APP_WIDGET_HOST_RESET = 2;

        private LauncherProviderChangeListener mListener;

        @Override
        public boolean handleMessage(Message msg) {
            if (mListener != null) {
                switch (msg.what) {
                    case MSG_LAUNCHER_PROVIDER_CHANGED:
                        mListener.onLauncherProviderChanged();
                        break;
                    case MSG_APP_WIDGET_HOST_RESET:
                        mListener.onAppWidgetHostReset();
                        break;
                }
            }
            return true;
        }
    }
}
