package me.aap.fermata.ui.home;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.fermata.ui.activity.MainActivityPrefs;
import me.aap.fermata.ui.view.InfoOverlayView;

/** Fills an {@link InfoOverlayView} from Settings > Info overlay, the way the Music tab does. */
public final class HomeInfo {
	private HomeInfo() {
	}

	/**
	 * Applies the overlay's settings (items, icons, order, size) to {@code overlay}; false when none of
	 * its items is switched on, so there is nothing to show.
	 */
	public static boolean apply(MainActivityDelegate ad, InfoOverlayView overlay) {
		MainActivityPrefs p = ad.getPrefs();
		boolean clock = p.getInfoOverlayShowClockPref(ad);
		boolean battery = p.getInfoOverlayShowBatteryPctPref(ad);
		boolean temp = p.getInfoOverlayShowBatteryTempPref(ad);
		boolean distance = p.getInfoOverlayShowDistancePref(ad);
		boolean dataUsage = p.getInfoOverlayShowDataUsagePref(ad);
		boolean dataRemaining = p.getInfoOverlayShowDataRemainingPref(ad);
		boolean downloaded = p.getInfoOverlayShowDownloadedPref(ad);
		boolean dataSession = p.getInfoOverlayShowDataSessionPref(ad);
		boolean power = p.getInfoOverlayShowPowerPref(ad);
		boolean any = clock || battery || temp || distance || dataUsage || dataRemaining || downloaded ||
				dataSession || power;
		if (!any) return false;
		overlay.setSize(p.getInfoOverlaySizePref(ad));
		overlay.setOrder(p.getInfoOverlayOrderPref(ad));
		overlay.setItems(clock, p.getInfoOverlayShowClockIconPref(ad), battery,
				p.getInfoOverlayShowBatteryIconPref(ad), temp, p.getInfoOverlayShowTempIconPref(ad), distance,
				p.getInfoOverlayShowDistanceIconPref(ad));
		overlay.setDataItems(dataUsage, dataRemaining, p.getInfoOverlayShowDataIconPref(ad));
		overlay.setSessionItem(dataSession, p.getInfoOverlayDataSessionBarPref(ad),
				p.getInfoOverlayDataSessionLeftPref(ad));
		overlay.setPowerItem(power, p.getInfoOverlayShowPowerIconPref(ad));
		overlay.setDownloadedItem(downloaded);
		return true;
	}
}
