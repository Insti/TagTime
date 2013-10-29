package bsoule.tagtime;

import java.util.ArrayList;
import java.util.List;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class BeeminderDbAdapter {
	private static final String TAG = "BeeminderDbAdapter";
	private static final boolean LOCAL_LOGV = false && !Timepie.DISABLE_LOGV;

	public static final String KEY_ROWID = "_id";

	// Table for goal registrations
	public static final String KEY_USERNAME = "user";
	public static final String KEY_SLUG = "slug";
	public static final String KEY_TOKEN = "token";

	// Table for goal tags
	public static final String KEY_GID = "goal_id";
	public static final String KEY_TID = "tag_id";

	// Table for datapoint submissions
	public static final String KEY_REQID = "req_id";
	public static final String KEY_VALUE = "value";
	public static final String KEY_TIMESTAMP = "time";
	public static final String KEY_COMMENT = "comment";
	// Uses KEY_GID
	public static final String KEY_PID = "ping_id";

	// Table for point ping pairs
	public static final String KEY_POINTID = "point_id";
	// Uses KEY_PID

	private static final String DATABASE_NAME = "timepie_beeminder";
	private static final String GOALS_TABLE = "goals";
	private static final String GOALTAGS_TABLE = "goaltags";
	private static final String POINTS_TABLE = "points";
	private static final String POINTPINGS_TABLE = "pointpings";
	private static final int DATABASE_VERSION = 1;

	private DatabaseHelper mDbHelper;
	private SQLiteDatabase mDb;

	/** Database creation sql statement */

	// a goal is a user/slug with a Beeminder token for submission
	private static final String CREATE_GOALS = "create table goals (_id integer primary key autoincrement, "
			+ "user text not null, slug text not null, token text not null, UNIQUE (user, slug));";

	// a goal tag is a goal-tag pairing
	private static final String CREATE_GOALTAGS = "create table goaltags (_id integer primary key autoincrement, "
			+ "goal_id integer not null, tag_id integer not null," + "UNIQUE (goal_id, tag_id));";

	// a point records submission details, corresponding goal and generating
	// ping
	private static final String CREATE_POINTS = "create table points (_id integer primary key autoincrement, "
			+ "req_id text not null, value real not null, time integer not null, comment text not null, goal_id integer not null,"
			+ "UNIQUE (req_id));";

	// a point ping is a point-ping pairing
	private static final String CREATE_POINTPINGS = "create table pointpings (_id integer primary key autoincrement, "
			+ "point_id integer not null, ping_id integer not null," + "UNIQUE (point_id, ping_id));";

	private final Context mCtx;

	// ===================== General Database Management =====================
	private static class DatabaseHelper extends SQLiteOpenHelper {

		DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			db.execSQL(CREATE_GOALS);
			db.execSQL(CREATE_GOALTAGS);
			db.execSQL(CREATE_POINTS);
			db.execSQL(CREATE_POINTPINGS);
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion
					+ ", which will destroy all old data");
			db.execSQL("DROP TABLE IF EXISTS goals");
			db.execSQL("DROP TABLE IF EXISTS goaltags");
			db.execSQL("DROP TABLE IF EXISTS points");
			db.execSQL("DROP TABLE IF EXISTS pointpings");
			onCreate(db);
		}
	}

	public BeeminderDbAdapter(Context ctx) {
		this.mCtx = ctx;
	}

	protected void deleteAllData() {
		mDbHelper.onUpgrade(mDb, 1, DATABASE_VERSION);
	}

	/**
	 * Open the database. If it cannot be opened, try to create a new instance
	 * of the database. If it cannot be created, throw an exception to signal
	 * the failure
	 * 
	 * @return this (self reference, allowing this to be chained in an
	 *         initialization call)
	 * @throws SQLException
	 *             if the database could be neither opened or created
	 */
	public BeeminderDbAdapter open() throws SQLException {
		mDbHelper = new DatabaseHelper(mCtx);
		mDb = mDbHelper.getWritableDatabase();
		return this;
	}

	public void close() {
		mDbHelper.close();
	}

	// ===================== Goal database utilities =====================
	public long createGoal(String user, String slug, String token, List<String> tags) {
		if (LOCAL_LOGV) Log.v(TAG, "createGoal()");
		long gid;
		try {
			gid = newGoal(user, slug, token);
		} catch (Exception e) {
			gid = getGoalID(user, slug);
		}
		if (!updateGoalTags(gid, tags)) {
			Log.e(TAG, "error creating the goal-tag entries");
		}
		return gid;
	}

	private long newGoal(String user, String slug, String token) throws Exception {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_USERNAME, user);
		initialValues.put(KEY_SLUG, slug);
		initialValues.put(KEY_TOKEN, token);
		return mDb.insertOrThrow(GOALS_TABLE, null, initialValues);
	}

	public boolean deleteGoal(long rowId) {
		updateGoalTags(rowId, new ArrayList<String>(0));
		return mDb.delete(GOALS_TABLE, KEY_ROWID + "=" + rowId, null) > 0;
	}

	public long getGoalID(String user, String slug) {
		if (LOCAL_LOGV) Log.v(TAG, "getGoalID(" + user + "/" + slug + ")");
		long gid = -1;
		Cursor cursor = mDb.query(GOALS_TABLE, new String[] { KEY_ROWID, KEY_USERNAME, KEY_SLUG }, KEY_USERNAME + "='"
				+ user + "' AND " + KEY_SLUG + "='" + slug + "'", null, null, null, null, null);
		if (cursor.getCount() > 0) {
			cursor.moveToFirst();
			gid = cursor.getLong(cursor.getColumnIndex(KEY_ROWID));
		}
		cursor.close();
		return gid;
	}

	public Cursor fetchGoal(long rowId) throws SQLException {
		Cursor pCursor = mDb.query(true, GOALS_TABLE, new String[] { KEY_ROWID, KEY_USERNAME, KEY_SLUG, KEY_TOKEN },
				KEY_ROWID + "=" + rowId, null, null, null, null, null);
		if (pCursor != null) {
			pCursor.moveToFirst();
		}
		return pCursor;

	}

	public Cursor fetchAllGoals() {
		return mDb.query(GOALS_TABLE, new String[] { KEY_ROWID, KEY_USERNAME, KEY_SLUG, KEY_TOKEN }, null, null, null,
				null, null);
	}

	public boolean updateGoal(long goalId, String user, String slug, String token) {
		// Insert authorization into the table
		ContentValues values = new ContentValues();
		values.put(KEY_USERNAME, user);
		values.put(KEY_SLUG, slug);
		values.put(KEY_TOKEN, token);
		int numrows = mDb.update(GOALS_TABLE, values, KEY_ROWID + " = " + goalId, null);

		if (numrows == 1) return true;
		else return false;

	}

	// ============== Goal-tag pair database utilities ===============
	public long newGoalTag(long goal_id, long tag_id) throws Exception {
		ContentValues init = new ContentValues();
		init.put(KEY_GID, goal_id);
		init.put(KEY_TID, tag_id);
		return mDb.insertOrThrow(GOALTAGS_TABLE, null, init);
	}

	public boolean isGoalTag(long gid, long tid) {
		Cursor c = mDb.query(GOALTAGS_TABLE, new String[] { KEY_ROWID }, KEY_GID + "=" + gid + " AND " + KEY_TID + "="
				+ tid, null, null, null, null);
		boolean ret = c.getCount() > 0;
		c.close();
		return ret;
	}

	public boolean deleteGoalTag(long goalId, long tagId) {
		String query = KEY_GID + "=" + goalId + " AND " + KEY_TID + "=" + tagId;
		return mDb.delete(GOALTAGS_TABLE, query, null) > 0;
	}

	public Cursor fetchGoalTags(long id, String col_key, String order) {
		return mDb.query(true, GOALTAGS_TABLE, new String[] { KEY_GID, KEY_TID }, col_key + " = " + id, null, null,
				null, KEY_PID + " DESC", null);
	}

	public Cursor fetchGoalTags(long id, String col_key) {
		return mDb.query(true, GOALTAGS_TABLE, new String[] { KEY_GID, KEY_TID }, col_key + " = " + id, null, null,
				null, null, null);
	}

	public String fetchTagString(long goal_id) throws Exception {
		Cursor c = mDb.query(GOALTAGS_TABLE, new String[] { KEY_GID, KEY_TID }, KEY_GID + "=" + goal_id, null, null,
				null, null);
		String s = "";
		PingsDbAdapter db = new PingsDbAdapter(mCtx);
		try {
			db.open();
			c.moveToFirst();
			int idx = c.getColumnIndex(KEY_TID);
			while (!c.isAfterLast()) {
				long tid = c.getLong(idx);
				String t = db.getTagName(tid);
				if (t.equals("")) {
					Exception e = new Exception("Could not find tag with id=" + tid);
					throw e;
				}
				s += t + " ";
				c.moveToNext();
			}
		} finally {
			c.close();
			db.close();
		}
		return s;
	}

	public List<String> fetchTagsForGoal(long goal_id) throws Exception {
		Cursor c = fetchGoalTags(goal_id, KEY_GID);
		List<String> ret = new ArrayList<String>();
		c.moveToFirst();
		int idx = c.getColumnIndex(KEY_TID);
		PingsDbAdapter db = new PingsDbAdapter(mCtx);
		db.open();
		while (!c.isAfterLast()) {
			long tid = c.getLong(idx);
			String t = db.getTagName(tid);
			if (t.equals("")) {
				db.close();
				Exception e = new Exception("Could not find tag with id=" + tid);
				throw e;
			}
			ret.add(t);
			c.moveToNext();
		}
		c.close();
		db.close();
		return ret;
	}

	public boolean updateGoalTags(long goalId, List<String> newTags) {
		if (LOCAL_LOGV) Log.v(TAG, "updateGoalTags()");
		// Remove all the old tags.
		List<String> cacheUpdates = new ArrayList<String>();
		try {
			cacheUpdates = fetchTagsForGoal(goalId);
		} catch (Exception e) {
			Log.w(TAG, "Some problem getting tags for this ping!");
			e.printStackTrace();
		}
		cacheUpdates.addAll(newTags);
		mDb.delete(GOALTAGS_TABLE, KEY_GID + "=" + goalId, null);
		PingsDbAdapter db = new PingsDbAdapter(mCtx);
		db.open();
		for (String t : newTags) {
			if (t.trim() == "") {
				continue;
			}
			long tid = db.getOrMakeNewTID(t);
			if (tid == -1) {
				Log.e(TAG, "ERROR: about to insert tid -1");
			}
			try {
				newGoalTag(goalId, tid);
			} catch (Exception e) {
				Log.w(TAG, "error inserting newGoalTag(" + goalId + "," + tid + ") in updateTaggings()");
			}
		}
		for (String tag : cacheUpdates) {
			db.updateTagCache(db.getTID(tag));
		}
		db.close();
		return true;
	}

	// ======================= Point database utilities ==================

	public long createPoint(String req_id, double value, long time, String comment, long goal_id) {
		if (LOCAL_LOGV) Log.v(TAG, "createPoint()");
		long gid;
		try {
			gid = newPoint(req_id, value, time, comment, goal_id);
		} catch (Exception e) {
			gid = getPointID(req_id);
		}
		// if (!updateGoalTags(gid, tags)) {
		// Log.e(TAG, "error creating the goal-tag entries");
		// }
		return gid;
	}

	private long newPoint(String req_id, double value, long time, String comment, long goal_id) throws Exception {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_REQID, req_id);
		initialValues.put(KEY_VALUE, value);
		initialValues.put(KEY_TIMESTAMP, time);
		initialValues.put(KEY_COMMENT, comment);
		initialValues.put(KEY_GID, goal_id);
		return mDb.insertOrThrow(POINTS_TABLE, null, initialValues);
	}

	private boolean deletePoint(long rowId) {
		// updateGoalTags(rowId, new ArrayList<String>(0));
		return mDb.delete(POINTS_TABLE, KEY_ROWID + "=" + rowId, null) > 0;
	}

	public long getPointID(String req_id) {
		if (LOCAL_LOGV) Log.v(TAG, "getPointID(" + req_id + ")");
		long gid = -1;
		Cursor cursor = mDb.query(POINTS_TABLE, new String[] { KEY_ROWID, KEY_REQID }, KEY_REQID + "='" + req_id + "'",
				null, null, null, null, null);
		if (cursor.getCount() > 0) {
			cursor.moveToFirst();
			gid = cursor.getLong(cursor.getColumnIndex(KEY_ROWID));
		}
		cursor.close();
		return gid;
	}

	public Cursor fetchPoint(long rowId) throws SQLException {
		Cursor pCursor = mDb.query(true, POINTS_TABLE, new String[] { KEY_ROWID, KEY_REQID, KEY_VALUE, KEY_TIMESTAMP,
				KEY_COMMENT, KEY_GID }, KEY_ROWID + "=" + rowId, null, null, null, null, null);
		if (pCursor != null) {
			pCursor.moveToFirst();
		}
		return pCursor;

	}

	public Cursor fetchAllPoints() {
		return mDb.query(POINTS_TABLE, new String[] { KEY_ROWID, KEY_REQID, KEY_VALUE, KEY_TIMESTAMP, KEY_COMMENT,
				KEY_GID }, null, null, null, null, null);
	}

	public boolean updatePoint(long pointId, double value, long time, String comment) {
		// Insert authorization into the table
		ContentValues values = new ContentValues();
		values.put(KEY_VALUE, value);
		values.put(KEY_TIMESTAMP, time);
		values.put(KEY_COMMENT, comment);
		int numrows = mDb.update(GOALS_TABLE, values, KEY_ROWID + " = " + pointId, null);

		if (numrows == 1) return true;
		else return false;
	}

	public void removePoint(long point_id) {
		List<Long> pings;
		deletePoint(point_id);
		try {
			pings = fetchPingsForPoint(point_id);
		} catch (Exception e) {
			Log.w(TAG, "removePoints: Error fetching pings for point "+point_id);
			return;
		}
		for (long ping_id : pings) {
			deletePointPing(point_id, ping_id);
		}
	}

	// ===================== Point-ping pair database utilities =============
	public long newPointPing(long point_id, long ping_id) throws Exception {
		ContentValues init = new ContentValues();
		init.put(KEY_POINTID, point_id);
		init.put(KEY_PID, ping_id);
		return mDb.insertOrThrow(POINTPINGS_TABLE, null, init);
	}

	public boolean isPointPing(long pointId, long pingId) {
		Cursor c = mDb.query(POINTPINGS_TABLE, new String[] { KEY_ROWID }, KEY_POINTID + "=" + pointId + " AND "
				+ KEY_PID + "=" + pingId, null, null, null, null);
		boolean ret = c.getCount() > 0;
		c.close();
		return ret;
	}

	public boolean deletePointPing(long pointId, long pingId) {
		String query = KEY_POINTID + "=" + pointId + " AND " + KEY_PID + "=" + pingId;
		return mDb.delete(POINTPINGS_TABLE, query, null) > 0;
	}

	public Cursor fetchPointPings(long id, String col_key) {
		return mDb.query(true, POINTPINGS_TABLE, new String[] { KEY_POINTID, KEY_PID }, col_key + " = " + id, null,
				null, null, null, null);
	}

	public List<Long> fetchPingsForPoint(long point_id) throws Exception {
		Cursor c = fetchPointPings(point_id, KEY_POINTID);
		List<Long> ret = new ArrayList<Long>();
		c.moveToFirst();
		int idx = c.getColumnIndex(KEY_PID);
		while (!c.isAfterLast()) {
			long tid = c.getLong(idx);
			ret.add(tid);
			c.moveToNext();
		}
		c.close();
		return ret;
	}
}