package me.aap.fermata.ui.home;

import androidx.annotation.Nullable;

import java.util.function.IntFunction;

/**
 * One setting of a widget, shown in its options panel: a switch, a choice between a few values, a
 * slider or a button. The value lives in the widget's options under {@link #key}.
 */
public final class HomeOption {
	public enum Kind {SWITCH, CHOICE, SLIDER, BUTTON}

	public final Kind kind;
	public final String key;
	public final CharSequence label;
	public CharSequence[] choices = new CharSequence[0];
	public int min;
	public int max = 100;
	public int def;
	public boolean defBool;
	@Nullable
	public IntFunction<String> format;
	@Nullable
	public Runnable action;

	private HomeOption(Kind kind, String key, CharSequence label) {
		this.kind = kind;
		this.key = key;
		this.label = label;
	}

	public static HomeOption sw(String key, CharSequence label, boolean def) {
		HomeOption o = new HomeOption(Kind.SWITCH, key, label);
		o.defBool = def;
		return o;
	}

	public static HomeOption choice(String key, CharSequence label, int def, CharSequence... choices) {
		HomeOption o = new HomeOption(Kind.CHOICE, key, label);
		o.def = def;
		o.choices = choices;
		return o;
	}

	public static HomeOption slider(String key, CharSequence label, int min, int max, int def,
																	@Nullable IntFunction<String> format) {
		HomeOption o = new HomeOption(Kind.SLIDER, key, label);
		o.min = min;
		o.max = max;
		o.def = def;
		o.format = format;
		return o;
	}

	public static HomeOption button(CharSequence label, Runnable action) {
		HomeOption o = new HomeOption(Kind.BUTTON, "", label);
		o.action = action;
		return o;
	}
}
