package com.devops.agent.infrastructure;

import com.github.junrar.Archive;
import com.github.junrar.unpack.vm.RarVM;
import com.github.junrar.unpack.vm.VMCommands;
import com.github.junrar.unpack.vm.VMOpType;
import com.github.junrar.unpack.vm.VMPreparedCommand;
import com.github.junrar.unpack.vm.VMPreparedProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 真实SDK：旧版也只最多取五次指令即硬退出，不执行会无限占CPU的恶意RAR。 */
@Timeout(15)
class JunrarSecurityCompatibilityTest {
    @Test
    void outOfRangeInstructionPointerIsRejected() throws Exception {
        var vm = new RarVM();
        var jump = RarVM.class.getDeclaredMethod("setIP", int.class);
        jump.setAccessible(true);
        set(vm, "codeSize", 2);
        set(vm, "maxOpCount", 4);
        assertEquals(Boolean.TRUE, jump.invoke(vm, 1), "健康的范围内跳转仍应接受");
        assertEquals(1, get(vm, "IP"));
        assertEquals(3, get(vm, "maxOpCount"));
        for (int target : new int[]{2, Integer.MAX_VALUE}) {
            set(vm, "IP", 0);
            set(vm, "maxOpCount", 4);
            assertEquals(Boolean.FALSE, jump.invoke(vm, target), "越界IP不能伪报跳转成功");
            assertEquals(0, get(vm, "IP"));
            assertEquals(4, get(vm, "maxOpCount"));
        }
    }

    @Test
    void executorHonorsRejectedJumpWithinBoundedFetches() throws Exception {
        for (int target : new int[]{2, Integer.MAX_VALUE}) {
            var vm = vm(target);
            var code = program();
            assertDoesNotThrow(() -> vm.execute(code.program), "SDK必须处理拒绝跳转；不能依赖时钟终止旧版死循环");
            assertEquals(2, code.instructions.fetches, "无效跳转后应前进到RET，而不是重新抓取同一JMP");
            assertEquals(1, get(vm, "IP"));
            assertEquals(0, code.program.getFilteredDataSize());
        }
    }

    @Test
    void finiteVmForwardJumpStillExecutes() throws Exception {
        var vm = vm(1);
        var code = program();
        assertDoesNotThrow(() -> vm.execute(code.program));
        assertEquals(2, code.instructions.fetches);
        assertEquals(1, get(vm, "IP"));
        assertEquals(0, code.program.getFilteredDataOffset());
    }

    @Test
    void realRarSdkReadsKnownFileBytes() throws Exception {
        try (var archive = new Archive(new ByteArrayInputStream(fixture("test.rar")))) {
            var files = archive.getFileHeaders().stream().filter(h -> !h.isDirectory()).toList();
            assertEquals(1, files.size());
            var file = files.getFirst();
            assertEquals("foo/bar.txt", file.getFileName().replace('\\', '/'));
            assertEquals(4, file.getFullUnpackSize());
            var output = new ByteArrayOutputStream();
            archive.extractFile(file, output);
            assertEquals("baz\n", output.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void configuredTikaParserExtractsRarMemberText() throws Exception {
        assertTrue(new DocumentParserConfig().documentParser()
                .parse(new ByteArrayInputStream(fixture("test.rar"))).text().contains("baz"));
    }

    @Test
    void unicodeRarHeaderNamesRemainReadable() throws Exception {
        try (var archive = new Archive(new ByteArrayInputStream(fixture("unicode.rar")))) {
            var names = archive.getFileHeaders().stream().map(h -> h.getFileName()).collect(Collectors.toSet());
            assertEquals(Set.of("新建文本文档.txt", "ウニコド.txt"), names);
        }
    }

    private static byte[] fixture(String name) throws Exception {
        try (var input = JunrarSecurityCompatibilityTest.class.getResourceAsStream("/junrar/" + name)) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            var hashes = Map.of("test.rar", "65f8921cab788afaa177f7c080c29a364a77eea91ee57c4390f5d4d14527da60",
                    "unicode.rar", "b35d9c57cab65a2f15827cb072aed971a99902d332f8c0ccf1ebcac69ab951e2");
            assertEquals(hashes.get(name), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            return bytes;
        }
    }

    private static RarVM vm(int target) {
        var vm = new RarVM();
        vm.init();
        // SDK getOperand读取mem[0..3]得到地址4，再从mem[4..7]得到跳转目标。
        vm.setLowEndianValue(vm.getMem(), 0, 4);
        vm.setLowEndianValue(vm.getMem(), 4, target);
        return vm;
    }

    private static Code program() {
        var jump = new VMPreparedCommand();
        jump.setOpCode(VMCommands.VM_JMP);
        jump.getOp1().setType(VMOpType.VM_OPINT);
        var ret = new VMPreparedCommand();
        ret.setOpCode(VMCommands.VM_RET);
        var instructions = new BoundedInstructions(List.of(jump, ret));
        var program = new VMPreparedProgram();
        program.setCmd(instructions);
        program.setCmdCount(2);
        program.setAltCmd(List.of());
        return new Code(program, instructions);
    }

    /** 只为输入List加确定性取指熔丝，不mock/改写SDK解释器，不以超时/大循环判红。 */
    private static final class BoundedInstructions extends AbstractList<VMPreparedCommand> {
        private final List<VMPreparedCommand> commands;
        private int fetches;

        BoundedInstructions(List<VMPreparedCommand> commands) {
            this.commands = commands;
        }

        @Override
        public VMPreparedCommand get(int index) {
            if (++fetches > 4) throw new IllegalStateException("fixture instruction-fetch fuse exceeded");
            return commands.get(index);
        }

        @Override
        public int size() {
            return commands.size();
        }
    }

    private record Code(VMPreparedProgram program, BoundedInstructions instructions) {}

    private static void set(RarVM vm, String name, int value) throws Exception {
        Field f = RarVM.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(vm, value);
    }

    private static int get(RarVM vm, String name) throws Exception {
        Field f = RarVM.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(vm);
    }
}
