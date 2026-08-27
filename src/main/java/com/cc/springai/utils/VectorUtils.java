package com.cc.springai.utils;

public final class VectorUtils {

    private VectorUtils() {
    }

    public static double euclideanDistance(float[] first, float[] second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException("Vectors must not be null.");
        }
        if (first.length != second.length) {
            throw new IllegalArgumentException("Vectors must have the same dimensions.");
        }

        double squaredDistance = 0.0;
        for (int index = 0; index < first.length; index++) {
            double difference = first[index] - second[index];
            squaredDistance += difference * difference;
        }
        return Math.sqrt(squaredDistance);
    }
}
