package com.shinoyuki.accesshub.latency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ProbeIdTest {

    @Test
    void generatedIdsAreRecognisedIncludingBoundaries() {
        int[] sequences = {0, 1, 0x00FF_FFFE, 0x00FF_FFFF, 0x0100_0000, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int sequence : sequences) {
            int id = ProbeId.of(sequence);
            assertTrue(ProbeId.isProbe(id),
                    "序号 " + sequence + " 生成的 id " + Integer.toHexString(id) + " 应被识别为探测包");
            assertNotEquals(0, id, "探测 id 不得为 0: 在飞表用 0 作空槽哨兵");
        }
    }

    /**
     * ping 的 id 是全服共享的 int 空间, 别的 mod / 插件也可能用。
     * 不带魔数的 id 必须一律拒收, 否则别人的 pong 会被算成延迟样本。
     */
    @Test
    void foreignIdsAreRejected() {
        int[] foreign = {0, 1, -1, 42, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x00FF_FFFF, 0xAB00_0000, 0xAD00_0000};
        for (int id : foreign) {
            assertFalse(ProbeId.isProbe(id), "不带魔数的 id 不应被认领: 0x" + Integer.toHexString(id));
        }
    }

    /** 24 位序号空间内不得出现碰撞, 否则在飞表会把两个不同探测配成同一条。 */
    @Test
    void sequencesWithinMaskDoNotCollide() {
        Random random = new Random(20260819L);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            int sequence = random.nextInt(0x0100_0000);
            int id = ProbeId.of(sequence);
            assertEquals(sequence, id & 0x00FF_FFFF, "低 24 位应原样保留序号");
            seen.add(id);
        }
        assertTrue(seen.size() > 4_900, "随机序号应几乎无碰撞, 实际去重后=" + seen.size());
    }
}
