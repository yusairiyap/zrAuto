package me.aap.fermata.ui.home.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays equal tiles out in rows, as many to a row as fit with the space left over spread between
 * them, and shows as many rows as there is height for: the tiles that don't fit get no room at all
 * (so neither the eye nor the steering wheel finds them). Used by the widgets that show a grid of
 * round icons.
 */
final class TileFlow extends ViewGroup {
	private int tileW;
	private int tileH;
	private final int minGap;
	private int cols = 1;
	private int vGap;

	TileFlow(Context ctx, int minGapPx) {
		super(ctx);
		minGap = minGapPx;
	}

	void setTile(int w, int h) {
		if ((tileW == w) && (tileH == h)) return;
		tileW = w;
		tileH = h;
		requestLayout();
	}

	@Override
	protected void onMeasure(int wSpec, int hSpec) {
		int w = MeasureSpec.getSize(wSpec);
		int h = MeasureSpec.getSize(hSpec);
		cols = Math.max(1, (w + minGap) / (tileW + minGap));
		int rows = Math.max(1, (h + minGap) / (tileH + minGap));
		int count = 0;
		for (int i = 0; i < getChildCount(); i++) {
			View c = getChildAt(i);
			if (c.getVisibility() == GONE) continue;
			c.measure(MeasureSpec.makeMeasureSpec(tileW, MeasureSpec.EXACTLY),
					MeasureSpec.makeMeasureSpec(tileH, MeasureSpec.EXACTLY));
			count++;
		}
		int usedRows = Math.min(rows, (count + cols - 1) / cols);
		vGap = (usedRows > 1) ? Math.max(minGap, (h - usedRows * tileH) / (usedRows - 1)) : minGap;
		vGap = Math.min(vGap, tileH / 2);
		setMeasuredDimension(w, h);
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		int w = r - l;
		int h = b - t;
		int rows = Math.max(1, (h + minGap) / (tileH + minGap));
		int hGap = (cols > 1) ? Math.max(minGap, (w - cols * tileW) / (cols - 1)) : 0;
		int rowsUsed = Math.min(rows, (getChildCount() + cols - 1) / cols);
		int used = rowsUsed * tileH + Math.max(0, rowsUsed - 1) * vGap;
		int top = Math.max(0, (h - used) / 2);
		int left = (cols > 1) ? 0 : (w - tileW) / 2;
		int n = 0;
		for (int i = 0; i < getChildCount(); i++) {
			View c = getChildAt(i);
			if (c.getVisibility() == GONE) continue;
			int row = n / cols;
			int col = n % cols;
			n++;
			if (row >= rows) {
				c.layout(0, 0, 0, 0);
				continue;
			}
			int x = left + col * (tileW + hGap);
			int y = top + row * (tileH + vGap);
			c.layout(x, y, x + tileW, y + tileH);
		}
	}
}
