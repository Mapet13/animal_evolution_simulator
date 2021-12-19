package agh.ics.oop;

public class WrappedWorldMap extends WorldMap {
    public WrappedWorldMap(AppConfig startingConfig, Jungle jungle, IDayChangeObserver dayChangeObserver) {
        super(startingConfig, jungle, dayChangeObserver);
    }

    @Override
    protected Vector2d getProperPosition(Vector2d position) {
        return position.moduloWith(startingConfig.MapWidth, startingConfig.MapHeight);
    }

    @Override
    protected MapTypes getMapType() {
        return MapTypes.Wrapped;
    }

    @Override
    public boolean canMoveTo(Vector2d pos) {
        return true;
    }
}
