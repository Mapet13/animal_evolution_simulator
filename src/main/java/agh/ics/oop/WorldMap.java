package agh.ics.oop;

import agh.ics.oop.utilities.IDProvider;
import agh.ics.oop.utilities.Pair;
import agh.ics.oop.utilities.UtilityFunctions;
import agh.ics.oop.utilities.Vector2d;
import javafx.application.Platform;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public abstract class WorldMap implements IMoveObserver, IMoveLimiter {
    private static final int magicalNumber = 5;
    protected final IDProvider animalIDProvider = new IDProvider();
    protected final ArrayList<Animal> livingAnimals = new ArrayList<>();
    protected final ArrayList<Animal> deadAnimals = new ArrayList<>();
    protected final Map<Vector2d, TreeSet<Animal>> animals = new LinkedHashMap<>();
    protected final ArrayList<Pair<Animal, Vector2d>> animalsToMove = new ArrayList<>();
    protected final Map<Vector2d, Grass> grasses = new LinkedHashMap<>();
    protected final HashSet<Vector2d> emptyPositions = new HashSet<>();
    protected final HashSet<Vector2d> changedTiles = new HashSet<>();
    protected final IDayChangeObserver dayChangeObserver;
    protected final AppConfig startingConfig;
    protected final int minCoordinate = 0;
    protected final int grassDailyIncrease = 1;
    protected final Jungle jungle;
    private final ReproductionSystem reproductionSystem = new ReproductionSystem(animalIDProvider, this);
    private final boolean isMagic;
    private int dayCount = 0;
    private int magicCounter = 3;

    public WorldMap(AppConfig startingConfig, Jungle jungle, IDayChangeObserver dayChangeObserver, boolean isMagic) {
        this.isMagic = isMagic;
        this.dayChangeObserver = dayChangeObserver;
        this.startingConfig = startingConfig;
        this.jungle = jungle;

        for (int i = 0; i < (int) startingConfig.get(AppConfig.Type.MapWidth); i++) {
            for (int j = 0; j < (int) startingConfig.get(AppConfig.Type.MapHeight); j++) {
                emptyPositions.add(new Vector2d(i, j));
            }
        }

        placeStartingObjects((int) startingConfig.get(AppConfig.Type.InitialAnimalCount), this::placeStartingAnimal);
        placeStartingObjects((int) startingConfig.get(AppConfig.Type.InitialGrassCount), () -> placeGrass(emptyPositions));
    }

    public Object objectAt(Vector2d position) {
        TreeSet<Animal> animal = specificObjectAt(animals, position);

        return animal != null && !animal.isEmpty()
                ? animal.first()
                : specificObjectAt(grasses, position);
    }

    private <T> T specificObjectAt(Map<Vector2d, T> objects, Vector2d position) {
        return objects.get(getProperPosition(position));
    }

    private void placeStartingObjects(int count, Runnable creationMethod) {
        IntStream.range(0, count).forEach(i -> creationMethod.run());
    }

    public int getDayCount() {
        return dayCount;
    }

    public void toNextDay() {
        if (isMagic && livingAnimals.size() == magicalNumber && magicCounter > 0)
            doMagic();

        changedTiles.clear();

        removeDeadAnimals();
        moveAnimals();
        feedAnimals();
        reproduceAnimals();
        addDailyGrasses();

        dayCount += 1;

        Platform.runLater(() -> dayChangeObserver.onDayChanged(changedTiles, getMapType()));
    }

    private void doMagic() {
        System.out.println("Doing Magic!");
        magicCounter -= 1;
        var copiedGenomes = livingAnimals.stream().map(Animal::getGenome).toList();
        copiedGenomes.forEach(copiedGenome -> addAnimalAtRandomPosition(new Genome(copiedGenome.genome())));
    }

    private void addAnimalAtRandomPosition(Genome genome) {
        addAnimals(pos -> new Animal(animalIDProvider.getNext(), pos, (int) startingConfig.get(AppConfig.Type.StartEnergy), this, genome));
    }

    private void placeStartingAnimal() {
        addAnimals(pos -> new Animal(animalIDProvider.getNext(), pos, (int) startingConfig.get(AppConfig.Type.StartEnergy), this));
    }

    private void addAnimals(Function<Vector2d, Animal> creator) {
        final Vector2d animalPos = getUniquePosition(emptyPositions.stream().toList()).orElse(getRandomPosition());
        addAnimalAtSpecificPosHolder(animalPos);
        Animal animal = creator.apply(animalPos);
        animals.get(animalPos).add(animal);
        livingAnimals.add(animal);
    }

    private void reproduceAnimals() {
        final int minimalParentsCount = 2;

        animals.values().stream()
                .filter(animalsOnSamePos -> animalsOnSamePos.size() >= minimalParentsCount)
                .filter(animalsOnSamePos ->
                        animalsOnSamePos.stream().limit(minimalParentsCount).allMatch(animal ->
                                animal.getEnergy() >= (int) startingConfig.get(AppConfig.Type.StartEnergy) * ReproductionSystem.minimalReproductionEnergyFactor))
                .forEach(animalsOnSamePos -> {
                    Animal[] parents = new Animal[]{animalsOnSamePos.first(), animalsOnSamePos.iterator().next()};
                    Animal child = reproductionSystem.createChildrenFrom(parents[0], parents[1]);
                    animalsOnSamePos.add(child);
                    livingAnimals.add(child);
                });
    }

    private void addDailyGrasses() {
        Map<Boolean, List<Vector2d>> positionsByTerrainType = emptyPositions.stream()
                .collect(Collectors.partitioningBy(jungle::isAt));

        IntStream.range(0, grassDailyIncrease).forEach(i -> placeGrass(positionsByTerrainType.get(true)));
        IntStream.range(0, grassDailyIncrease).forEach(i -> placeGrass(positionsByTerrainType.get(false)));
    }

    private void feedAnimals() {
        grasses.entrySet().removeIf(grass -> {
            var animalAtPos = animals.get(grass.getKey());
            if (animalAtPos != null && animalAtPos.size() > 0) {
                var animalWithBiggestEnergyValue = animalAtPos.stream().filter(animal -> animal.getEnergy() == animalAtPos.first().getEnergy()).toList();
                animalWithBiggestEnergyValue.forEach(animal -> animal.eat((int) startingConfig.get(AppConfig.Type.PlantEnergy) / animalWithBiggestEnergyValue.size()));
                return true;
            }
            return false;
        });
    }

    private void moveAnimals() {
        animals.forEach((key, value) -> {
            ArrayList<Animal> animalAtPos = new ArrayList<>();
            value.forEach(animal -> {
                animal.move((int) startingConfig.get(AppConfig.Type.MoveEnergy), this);
                animalAtPos.add(animal);
            });
            value.clear();
            value.addAll(animalAtPos);

            changedTiles.add(key);
        });
        animalsToMove.forEach(pair -> moveAnimalOnMap(pair.first(), pair.second()));
        animalsToMove.clear();
    }

    private void removeDeadAnimals() {
        animals.keySet().forEach(pos -> {
            var animalsToRemove = animals.get(pos).stream().filter(Animal::isDead).toList();
            for (var animal : animalsToRemove) {
                animals.get(pos).remove(animal);
                livingAnimals.remove(animal);
                deadAnimals.add(animal);
            }
            if (animals.get(pos).isEmpty()) {
                emptyPositions.add(pos);
            }
        });
    }

    private Vector2d getRandomPosition() {
        return Vector2d.MapFrom(arg -> UtilityFunctions.randFromRange(minCoordinate, arg),
                (int) startingConfig.get(AppConfig.Type.MapWidth),
                (int) startingConfig.get(AppConfig.Type.MapHeight));
    }

    private void placeGrass(Collection<Vector2d> availablePositions) {
        getUniquePosition(availablePositions).ifPresent(pos -> {
            grasses.put(pos, new Grass());
            emptyPositions.remove(pos);
            changedTiles.add(pos);
        });
    }

    private Optional<Vector2d> getUniquePosition(Collection<Vector2d> availablePositions) {
        if (availablePositions.isEmpty())
            return Optional.empty();

        return Optional.of((Vector2d) availablePositions.toArray()[new Random().nextInt(availablePositions.size())]);
    }

    private void addAnimalAtSpecificPosHolder(Vector2d position) {
        emptyPositions.remove(position);
        animals.putIfAbsent(position, new TreeSet<>(Comparator.comparingInt(Animal::getEnergy).thenComparing(Animal::getID)));
    }

    @Override
    public void onAnimalMove(Animal animal, Vector2d oldPosition) {
        animalsToMove.add(new Pair<>(animal, oldPosition));
    }

    private void moveAnimalOnMap(Animal animal, Vector2d oldPosition) {
        oldPosition = getProperPosition(oldPosition);

        changedTiles.add(getProperPosition(animal.getPosition()));

        animals.get(oldPosition).remove(animal);

        Vector2d currentPosition = getProperPosition(animal.getPosition());
        if (!animals.containsKey(currentPosition))
            addAnimalAtSpecificPosHolder(currentPosition);

        animals.get(currentPosition).add(animal);

        if (animals.get(oldPosition).isEmpty()) {
            emptyPositions.add(oldPosition);
        }
    }

    abstract public Vector2d getProperPosition(Vector2d position);

    abstract protected MapTypes getMapType();

    public int getAnimalCount() {
        return livingAnimals.size();
    }

    public int getGrassCount() {
        return grasses.size();
    }

    public float getAverageEnergyLevel() {
        return UtilityFunctions.getAverageOfList(livingAnimals, Animal::getEnergy);
    }

    public float getAverageLengthOfLife() {
        return UtilityFunctions.getAverageOfList(deadAnimals, Animal::getAge);
    }

    public boolean haveAnyDeadAnimals() {
        return !deadAnimals.isEmpty();
    }

    public float getAverageChildrenCount() {
        return UtilityFunctions.getAverageOfList(livingAnimals, Animal::getChildrenCount);
    }

    public Genome getMostCommonGenome() {
        Map<Genome, Integer> map = new HashMap<>();
        livingAnimals.stream().map(Animal::getGenome).map(Genome::getSorted).forEach(t -> map.compute(t, (k, i) -> i == null ? 1 : i + 1));
        return Collections.max(map.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    public List<Animal> getAnimalsWithGenome(Genome genome) {
        Genome sorted = genome.getSorted();
        return livingAnimals.stream().filter(animal -> animal.getGenome().getSorted().equals(sorted)).toList();
    }

}
