package com.yuyin.music.mobile;

final class PlaybackQueue {
    private PlaybackQueue() {}
    static int next(int length, int index, String mode, int direction, boolean automatic, double random) {
        if (length < 1) return -1;
        if (index < 0 || index >= length) return direction < 0 ? length - 1 : 0;
        if (length == 1 || (automatic && "repeat".equals(mode))) return index;
        if ("shuffle".equals(mode)) { int offset = (int) (Math.max(0, Math.min(.999999999, random)) * (length - 1)); return offset >= index ? offset + 1 : offset; }
        return (index + (direction < 0 ? -1 : 1) + length) % length;
    }
}
