package me.aap.fermata.addon.sleep;

import android.util.AtomicFile;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import me.aap.fermata.FermataApplication;
import me.aap.fermata.util.DiagnosticLog;
import me.aap.fermata.util.LogCat;
import me.aap.utils.app.App;
import me.aap.utils.log.Log;

/**
 * The recorded nights, kept for three years in a small text file in the app's files dir (one night per line, JSON), like the
 * data and power usage records. The night being recorded is saved every few minutes to a second file, so an app that is killed
 * in the middle of the night does not lose it ({@link SleepTracker} finishes it at the next start).
 */
public final class SleepStore {
	private static final String FILE_NAME = "sleep_nights.txt";
	private static final String DRAFT_NAME = "sleep_draft.txt";
	private static final long KEEP_MS = 3 * 366L * 24 * 3600_000L;

	private final List<SleepNight> nights = new ArrayList<>();
	private final AtomicFile file;
	private final AtomicFile draftFile;
	private boolean saving;
	private boolean dirty;

	SleepStore() {
		File dir = FermataApplication.get().getFilesDir();
		file = new AtomicFile(new File(dir, FILE_NAME));
		draftFile = new AtomicFile(new File(dir, DRAFT_NAME));
		load();
	}

	/** The nights, oldest first. A copy. */
	public synchronized List<SleepNight> all() {
		return new ArrayList<>(nights);
	}

	public synchronized int size() {
		return nights.size();
	}

	@Nullable
	public synchronized SleepNight last() {
		return nights.isEmpty() ? null : nights.get(nights.size() - 1);
	}

	synchronized void add(SleepNight n) {
		nights.removeIf(o -> o.start == n.start);
		nights.add(n);
		nights.sort((a, b) -> Long.compare(a.start, b.start));
		long limit = System.currentTimeMillis() - KEEP_MS;
		nights.removeIf(o -> o.start < limit);
		dirty = true;
		scheduleSave();
	}

	synchronized void remove(SleepNight n) {
		if (nights.removeIf(o -> o.start == n.start)) {
			dirty = true;
			scheduleSave();
		}
	}

	public synchronized void clear() {
		nights.clear();
		dirty = true;
		scheduleSave();
	}

	// ---------------------------------------------------------------------------------------------
	// The night being recorded

	void saveDraft(SleepNight n) {
		try {
			JSONObject o = n.toJson();
			synchronized (draftFile) {
				FileOutputStream out = draftFile.startWrite();
				try {
					out.write(o.toString().getBytes(StandardCharsets.UTF_8));
					draftFile.finishWrite(out);
				} catch (Exception ex) {
					draftFile.failWrite(out);
					throw ex;
				}
			}
		} catch (Exception ex) {
			DiagnosticLog.w(LogCat.SLEEP, "draft not saved", ex);
		}
	}

	@Nullable
	SleepNight readDraft() {
		synchronized (draftFile) {
			if (!draftFile.getBaseFile().isFile()) return null;
			try {
				String s = new String(draftFile.readFully(), StandardCharsets.UTF_8);
				return SleepNight.fromJson(new JSONObject(s));
			} catch (Exception ex) {
				DiagnosticLog.w(LogCat.SLEEP, "draft unreadable", ex);
				return null;
			}
		}
	}

	void deleteDraft() {
		synchronized (draftFile) {
			draftFile.delete();
		}
	}

	// ---------------------------------------------------------------------------------------------
	// The file

	private void load() {
		if (!file.getBaseFile().isFile()) return;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(file.openRead(), StandardCharsets.UTF_8))) {
			for (String l = r.readLine(); l != null; l = r.readLine()) {
				if (l.isEmpty()) continue;
				try {
					nights.add(SleepNight.fromJson(new JSONObject(l)));
				} catch (Exception ex) {
					DiagnosticLog.w(LogCat.SLEEP, "night unreadable", ex);
				}
			}
		} catch (Exception ex) {
			Log.e(ex, "Failed to read the sleep record");
		}
		nights.sort((a, b) -> Long.compare(a.start, b.start));
	}

	private void scheduleSave() {
		if (saving) return;
		saving = true;
		App.get().getHandler().postDelayed(() -> App.get().execute(this::save), 1500);
	}

	private void save() {
		List<SleepNight> copy;
		synchronized (this) {
			saving = false;
			if (!dirty) return;
			dirty = false;
			copy = new ArrayList<>(nights);
		}
		FileOutputStream out = null;
		try {
			out = file.startWrite();
			Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
			for (SleepNight n : copy) w.write(n.toJson().toString() + '\n');
			w.flush();
			file.finishWrite(out);
		} catch (Exception ex) {
			if (out != null) file.failWrite(out);
			Log.e(ex, "Failed to save the sleep record");
		}
	}
}
