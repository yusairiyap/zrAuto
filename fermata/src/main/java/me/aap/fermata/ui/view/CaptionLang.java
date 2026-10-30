package me.aap.fermata.ui.view;

import static me.aap.utils.async.Completed.completedVoid;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.Locale;

import me.aap.fermata.R;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ytcaptions.CaptionsHub;
import me.aap.fermata.ytcaptions.YtCaptions;

/**
 * Changing the language of the lyrics and captions on the fly: one menu (opened from the lyrics page and card, the Music
 * tab's Lyrics chip, the lyrics widget and the captions panel) with the language, the Latin letters under Korean and
 * Japanese lines and the translated second line, and one line saying which language is shown now.
 */
public final class CaptionLang {
	/** The languages offered; the empty code is the phone's own. */
	public static final String[] CODES = {"", "en", "ms", "id", "ar", "es", "fr", "de", "pt", "ru", "tr", "hi", "ja", "ko", "zh"};

	private CaptionLang() {
	}

	public static String name(Context ctx, String code) {
		return code.isEmpty() ? ctx.getString(R.string.captions_lang_phone) :
				new Locale(code).getDisplayLanguage(Locale.getDefault());
	}

	/** The language the lines are in now, e.g. "Korean · auto-generated" or "English (translated)"; the chosen one until known. */
	public static String current(Context ctx) {
		MainActivityPrefs p = MainActivityPrefs.get();
		YtCaptions.Track t = CaptionsHub.get().getTrack();
		StringBuilder sb = new StringBuilder();
		if (t != null) {
			sb.append(name(ctx, t.language.split("-")[0]));
			if (t.translatedFrom != null) sb.append(" (").append(ctx.getString(R.string.captions_translated)).append(')');
			else if (t.generated) sb.append(" · ").append(ctx.getString(R.string.captions_auto_label));
		} else {
			sb.append(name(ctx, p.getStringPref(MainActivityPrefs.CAPTIONS_LANG)));
		}
		if (p.getBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI)) sb.append(" + ").append(ctx.getString(R.string.captions_romaji_short));
		String tr = p.getStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION);
		if (!tr.isEmpty()) sb.append(" + ").append(name(ctx, tr));
		return sb.toString();
	}

	/** Sets the language and has the lines fetched again. */
	public static void setLanguage(String code) {
		MainActivityPrefs.get().applyStringPref(MainActivityPrefs.CAPTIONS_LANG, code);
		CaptionsHub.get().invalidate();
	}

	public static void setTranslation(String code) {
		MainActivityPrefs.get().applyStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION, code);
		CaptionsHub.get().invalidate();
	}

	public static void toggleRomajiTo(boolean on) {
		MainActivityPrefs.get().applyBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI, on);
		CaptionsHub.get().invalidate();
	}

	public static void toggleRomaji() {
		MainActivityPrefs p = MainActivityPrefs.get();
		p.applyBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI, !p.getBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI));
		CaptionsHub.get().invalidate();
	}

	/** A short word for the chip: the language shown now, e.g. "EN" or "KO". */
	public static String shortLabel() {
		YtCaptions.Track t = CaptionsHub.get().getTrack();
		String c = (t != null) ? t.language : MainActivityPrefs.get().getStringPref(MainActivityPrefs.CAPTIONS_LANG);
		c = c.split("-")[0];
		return c.isEmpty() ? "CC" : c.toUpperCase(Locale.ROOT);
	}

	/** Only the translated second line's languages: opened from the captions panel, which lists the main language itself. */
	public static void showTranslationMenu(MainActivityDelegate a, @Nullable Runnable after) {
		Context ctx = a.getContext();
		String tr = a.getPrefs().getStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION);
		raise(a);
		a.getContextMenu().showFuture(b -> {
			b.setTitle(R.string.captions_translation);
			b.addItem(R.id.caption_menu_item, (tr.isEmpty() ? "✓  " : "") + ctx.getString(R.string.captions_translation_off))
					.setHandler(i -> {
						setTranslation("");
						if (after != null) after.run();
						return true;
					});
			for (String c : CODES) {
				if (c.isEmpty()) continue;
				b.addItem(R.id.caption_menu_item, (c.equals(tr) ? "✓  " : "") + name(ctx, c)).setHandler(i -> {
					setTranslation(c);
					if (after != null) after.run();
					return true;
				});
			}
			return completedVoid();
		});
	}

	/** The context menu is under a modal card (30dp) by default: this one is drawn over it. */
	private static void raise(MainActivityDelegate a) {
		if (a.getContextMenu() instanceof android.view.View v) v.setElevation(40f * v.getResources().getDisplayMetrics().density);
	}

	/** The menu: language, Latin letters, translation. {@code after} runs when something was changed. */
	public static void showMenu(MainActivityDelegate a, @Nullable Runnable after) {
		Context ctx = a.getContext();
		MainActivityPrefs p = a.getPrefs();
		String lang = p.getStringPref(MainActivityPrefs.CAPTIONS_LANG);
		String tr = p.getStringPref(MainActivityPrefs.CAPTIONS_TRANSLATION);
		raise(a);
		a.getContextMenu().showFuture(b -> {
			b.setTitle(current(ctx));
			b.addItem(R.id.caption_menu_lang, R.drawable.captions, ctx.getString(R.string.captions_lang)).setSubmenu(s -> {
				for (String c : CODES) {
					s.addItem(R.id.caption_menu_item, ((c.equals(lang) ? "✓  " : "") + name(ctx, c))).setHandler(i -> {
						setLanguage(c);
						if (after != null) after.run();
						return true;
					});
				}
			});
			b.addItem(R.id.caption_menu_romaji, R.drawable.lyrics,
					(p.getBooleanPref(MainActivityPrefs.CAPTIONS_ROMAJI) ? "✓  " : "") + ctx.getString(R.string.captions_romaji))
					.setHandler(i -> {
						toggleRomaji();
						if (after != null) after.run();
						return true;
					});
			b.addItem(R.id.caption_menu_translation, R.drawable.captions, ctx.getString(R.string.captions_translation))
					.setSubmenu(s -> {
						s.addItem(R.id.caption_menu_item, (tr.isEmpty() ? "✓  " : "") + ctx.getString(R.string.captions_translation_off))
								.setHandler(i -> {
									setTranslation("");
									if (after != null) after.run();
									return true;
								});
						for (String c : CODES) {
							if (c.isEmpty()) continue;
							s.addItem(R.id.caption_menu_item, (c.equals(tr) ? "✓  " : "") + name(ctx, c)).setHandler(i -> {
								setTranslation(c);
								if (after != null) after.run();
								return true;
							});
						}
					});
			return completedVoid();
		});
	}
}
