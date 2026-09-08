package dev.petbees;

public final class BeeRules {
    private BeeRules() {}
    public static float healthAfterSting(float health) {
        return health <= 1 ? 0 : Math.max(1, health - 5);
    }
    public static boolean tames(int attempt, float roll) {
        return attempt >= 3 || roll < 0.3F;
    }
}
