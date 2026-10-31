package me.aap.fermata.ytcaptions;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes Korean (Hangul) and Japanese kana in Latin letters, so a song can be sung along to without reading the script:
 * Korean by the Revised Romanization of Korea (each syllable on its own, without the sound changes between syllables)
 * and kana by Hepburn (the long vowel mark repeats the vowel, a small tsu doubles the next consonant). Kanji and every
 * other character are left as they are, since reading them needs a dictionary; a line with none of the two scripts
 * gives null.
 */
public final class Romanizer {
	private static final String[] INITIAL = {"g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k",
			"t", "p", "h"};
	private static final String[] MEDIAL = {"a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u",
			"wo", "we", "wi", "yu", "eu", "ui", "i"};
	private static final String[] FINAL = {"", "k", "kk", "ks", "n", "nj", "nh", "t", "l", "lk", "lm", "lb", "ls", "lt", "lp",
			"lh", "m", "p", "ps", "t", "t", "ng", "t", "t", "k", "t", "p", "t"};

	private static final Map<String, String> KANA = new HashMap<>();

	static {
		String[] base = {"あa", "いi", "うu", "えe", "おo", "かka", "きki", "くku", "けke", "こko", "さsa", "しshi", "すsu", "せse",
				"そso", "たta", "ちchi", "つtsu", "てte", "とto", "なna", "にni", "ぬnu", "ねne", "のno", "はha", "ひhi", "ふfu",
				"へhe", "ほho", "まma", "みmi", "むmu", "めme", "もmo", "やya", "ゆyu", "よyo", "らra", "りri", "るru", "れre",
				"ろro", "わwa", "をo", "んn", "がga", "ぎgi", "ぐgu", "げge", "ごgo", "ざza", "じji", "ずzu", "ぜze", "ぞzo",
				"だda", "ぢji", "づzu", "でde", "どdo", "ばba", "びbi", "ぶbu", "べbe", "ぼbo", "ぱpa", "ぴpi", "ぷpu", "ぺpe",
				"ぽpo", "ゔvu", "ぁa", "ぃi", "ぅu", "ぇe", "ぉo"};
		for (String b : base) KANA.put(b.substring(0, 1), b.substring(1));
		// きゃ きゅ きょ and the like: the i-row's consonant with a small ya / yu / yo.
		String[] rows = {"きk", "しsh", "ちch", "にny", "ひhy", "みmy", "りry", "ぎgy", "じj", "ぢj", "びby", "ぴpy"};
		String[] small = {"ゃa", "ゅu", "ょo"};
		for (String r : rows) {
			String c = r.substring(1);
			for (String s : small) {
				String v = s.substring(1);
				boolean plain = c.equals("sh") || c.equals("ch") || c.equals("j");
				KANA.put(r.substring(0, 1) + s.substring(0, 1), plain ? c + v : (c.endsWith("y") ? c : c + "y") + v);
			}
		}
	}

	private Romanizer() {
	}

	/** {@code text} in Latin letters, or null when it holds no Hangul and no kana. */
	@Nullable
	public static String romanize(@Nullable CharSequence text) {
		if (text == null) return null;
		int n = text.length();
		StringBuilder sb = new StringBuilder(n * 2);
		boolean any = false;
		boolean doubleNext = false;
		for (int i = 0; i < n; i++) {
			char ch = text.charAt(i);
			if ((ch >= 0xAC00) && (ch <= 0xD7A3)) {
				int s = ch - 0xAC00;
				sb.append(INITIAL[s / 588]).append(MEDIAL[(s % 588) / 28]).append(FINAL[s % 28]);
				any = true;
				continue;
			}
			// Katakana is the hiragana table shifted.
			char h = ((ch >= 0x30A1) && (ch <= 0x30F6)) ? (char) (ch - 0x60) : ch;
			if (h == 0x30FC) {
				// The long vowel mark: the vowel before it again.
				if (sb.length() > 0) {
					char last = sb.charAt(sb.length() - 1);
					if ("aiueo".indexOf(last) >= 0) sb.append(last);
				}
				any = true;
				continue;
			}
			if (h == 'っ') {
				doubleNext = true;
				any = true;
				continue;
			}
			String r = null;
			if (i + 1 < n) {
				char nx = text.charAt(i + 1);
				char nh = ((nx >= 0x30A1) && (nx <= 0x30F6)) ? (char) (nx - 0x60) : nx;
				if ((nh == 'ゃ') || (nh == 'ゅ') || (nh == 'ょ')) {
					r = KANA.get("" + h + nh);
					if (r != null) i++;
				}
			}
			if (r == null) r = KANA.get(String.valueOf(h));
			if (r != null) {
				if (doubleNext && !r.isEmpty()) {
					sb.append(r.startsWith("ch") ? "t" : r.substring(0, 1));
				}
				doubleNext = false;
				sb.append(r);
				any = true;
				continue;
			}
			doubleNext = false;
			// A Japanese or Korean mark between words: the plain ones.
			if (ch == '、' || ch == '，') sb.append(',');
			else if (ch == '。') sb.append('.');
			else if (ch == '　') sb.append(' ');
			else sb.append(ch);
		}
		if (!any) return null;
		String out = sb.toString().replaceAll("\\s+", " ").trim();
		return out.isEmpty() ? null : out;
	}
}
