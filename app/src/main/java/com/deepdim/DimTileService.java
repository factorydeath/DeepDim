package com.deepdim;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Плитка быстрых настроек: тап включает и выключает затемнение. */
public final class DimTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        Prefs prefs = Prefs.get(this);
        boolean currentlyOn = DimService.isActive() || prefs.enabled();
        DimService.startAction(this,
                currentlyOn ? DimService.ACTION_DISABLE : DimService.ACTION_ENABLE);
        updateTile();
    }

    private void updateTile() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            Prefs prefs = Prefs.get(this);
            boolean on = DimService.isActive() || prefs.enabled();
            tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.tile_label));
            tile.setContentDescription(getString(on ? R.string.tile_on : R.string.tile_off));
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(on ? effectivePercent() + "%" : "");
            }
            tile.updateTile();
        } catch (Throwable ignored) {
        }
    }

    private int effectivePercent() {
        DimService service = DimService.peek();
        if (service != null) return service.getState().effectiveDim();
        return Prefs.get(this).dim();
    }
}
