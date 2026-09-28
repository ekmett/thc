// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.source.Source;
import org.graalvm.polyglot.io.ByteSequence;
import java.util.Set;
import static thc.runtime.RuntimeFault.fault;

/** Bounded synchronous native GMP over checked guest limb storage. */
public final class SulongLimbProvider implements LimbProvider {
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final Object library;
    private final Object add;
    private final Object addWord;
    private final Object subtract;
    private final Object compare;
    private final Object multiply;
    private final Object multiplyWord;
    private final Object divideWord;
    private final Object moduloWord;
    private final Object divide;
    private final Object quotient;
    private final Object remainder;
    private final Object shiftRight;
    private final Object getDouble;
    private final Object gcdWords;
    private final Object gcdWord;
    private final Object gcd;
    private final Object shiftLeft;
    private final Object and;
    private final Object andNot;
    private final Object or;
    private final Object xor;
    private final Object populationCount;
    public SulongLimbProvider(TruffleLanguage.Env env) {
        if (!env.isNativeAccessAllowed()) throw fault("Native limb arithmetic requires native access");
        if (!System.getProperty("os.name").equals("Linux") || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch")))
            throw fault("Native GMP limb transport is currently verified only on Linux x86_64");
        try (var stream = getClass().getResourceAsStream("/thc/cbits/gmp-api.so")) {
            if (stream == null) throw fault("Missing compiled native GMP adapter");
            library = env.parseInternal(Source.newBuilder("llvm", ByteSequence.create(stream.readAllBytes()), "gmp-api.so").build()).call();
            if (interop.asInt(interop.execute(interop.readMember(library, "thc_gmp_limb_bits"))) != 64)
                throw fault("Native GMP library does not use 64-bit limbs");
            add = interop.readMember(library, "thc_gmp_add");
            addWord = interop.readMember(library, "thc_gmp_add_word");
            subtract = interop.readMember(library, "thc_gmp_subtract");
            compare = interop.readMember(library, "thc_gmp_compare");
            multiply = interop.readMember(library, "thc_gmp_multiply");
            multiplyWord = interop.readMember(library, "thc_gmp_multiply_word");
            divideWord = interop.readMember(library, "thc_gmp_divide_word");
            moduloWord = interop.readMember(library, "thc_gmp_modulo_word");
            divide = interop.readMember(library, "thc_gmp_divide");
            quotient = interop.readMember(library, "thc_gmp_quotient");
            remainder = interop.readMember(library, "thc_gmp_remainder");
            shiftRight = interop.readMember(library, "thc_gmp_shift_right");
            getDouble = interop.readMember(library, "thc_gmp_get_double");
            gcdWords = interop.readMember(library, "thc_gmp_gcd_words");
            gcdWord = interop.readMember(library, "thc_gmp_gcd_word");
            gcd = interop.readMember(library, "thc_gmp_gcd");
            shiftLeft = interop.readMember(library, "thc_gmp_shift_left");
            and = interop.readMember(library, "thc_gmp_and");
            andNot = interop.readMember(library, "thc_gmp_and_not");
            or = interop.readMember(library, "thc_gmp_or");
            xor = interop.readMember(library, "thc_gmp_xor");
            populationCount = interop.readMember(library, "thc_gmp_popcount");
        } catch (Exception failure) { throw rethrow(failure); }
    }
    private static void preflight(LimbRegion region) {
        region.validate();
        region.getAddress().cbitsSegment();
    }
    private static NativeLimbScope.Pointer snapshot(LimbRegion region, NativeLimbScope scope) {
        var address = region.getAddress();
        var owner = address.cbitsOwner();
        synchronized (owner != null ? owner : address.cbitsStorageKey()) {
            address.requireRange(0, region.getByteSize(), false);
            return owner != null && owner.hasNativeStorage() ? scope.borrow(address, region.getByteSize())
                : scope.snapshot(address.cbitsBacking(), (int) address.cbitsOffset(), (int) region.getByteSize());
        }
    }
    private static NativeLimbScope.Pointer destination(LimbRegion region, NativeLimbScope scope) {
        var address = region.getAddress();
        var owner = address.cbitsOwner();
        return owner != null && owner.hasNativeStorage() ? scope.borrow(address, region.getByteSize()) : scope.allocate(region.getByteSize());
    }
    private static void copyFrom(LimbRegion region, NativeLimbScope.Pointer pointer) { copyFrom(region, pointer, region.getLimbs()); }
    private static void copyFrom(LimbRegion region, NativeLimbScope.Pointer pointer, long written) {
        var address = region.getAddress();
        var owner = address.cbitsOwner();
        synchronized (owner != null ? owner : address.cbitsStorageKey()) {
            region.requireOutput(region.getLimbs());
            if (written < 0 || written > region.getLimbs()) throw fault("Invalid native limb result count");
            if (!pointer.aliases(address)) pointer.copyTo(address.cbitsSegment(), address.cbitsOffset(), written * 8);
        }
    }
    private static void exactAlias(LimbRegion output, LimbRegion input) {
        if (output.overlaps(input) && !output.sameStart(input)) throw fault("Partial limb overlap is not allowed");
    }
    private static void disjoint(LimbRegion first, LimbRegion second) {
        if (first.overlaps(second)) throw fault("This limb operation requires disjoint regions");
    }
    private static void ordered(LimbRegion left, LimbRegion right) {
        left.requirePositive(); right.requirePositive();
        if (left.getLimbs() < right.getLimbs()) throw fault("Left limb count is smaller than right limb count");
    }
    private static void nonzero(long divisor) { if (divisor == 0) throw fault("Native limb division by zero"); }
    private static void normalized(LimbRegion divisor, NativeLimbScope.Pointer pointer) {
        if (pointer.readWord(divisor.getLimbs() - 1) == 0) throw fault("Divisor most significant limb is zero");
    }
    private static void divisionInputs(LimbRegion numerator, LimbRegion divisor) {
        ordered(numerator, divisor); disjoint(numerator, divisor);
    }

    private long addSubtract(LimbRegion output, LimbRegion left, LimbRegion right, Object operation) {
        ordered(left, right); output.requireOutput(left.getLimbs());
        exactAlias(output, left); exactAlias(output, right);
        preflight(output); preflight(left); preflight(right);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(left, scope); var second = snapshot(right, scope);
            var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(operation, destination, first, left.getLimbs(), second, right.getLimbs()));
            copyFrom(output, destination);
            return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long add(LimbRegion output, LimbRegion left, LimbRegion right) {
        return addSubtract(output, left, right, add);
    }

    @TruffleBoundary @Override public long subtract(LimbRegion output, LimbRegion left, LimbRegion right) {
        return addSubtract(output, left, right, subtract);
    }

    private long wordOperation(LimbRegion output, LimbRegion input, long value, Object operation) {
        input.requirePositive(); output.requireOutput(input.getLimbs());
        preflight(output); preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var source = snapshot(input, scope); var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(operation, destination, source, input.getLimbs(), value));
            copyFrom(output, destination);
            return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long addWord(LimbRegion output, LimbRegion input, long word) {
        exactAlias(output, input); return wordOperation(output, input, word, addWord);
    }

    @TruffleBoundary @Override public long multiplyWord(LimbRegion output, LimbRegion input, long word) {
        if (output.overlaps(input) && output.getAddress().cbitsOffset() > input.getAddress().cbitsOffset())
            throw fault("Multiply-word destination overlaps above input");
        return wordOperation(output, input, word, multiplyWord);
    }

    @TruffleBoundary @Override public long compare(LimbRegion left, LimbRegion right) {
        ordered(left, right);
        if (left.getLimbs() != right.getLimbs()) throw fault("Limb compare requires equal counts");
        preflight(left); preflight(right);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            return interop.asLong(interop.execute(compare, snapshot(left, scope), snapshot(right, scope), left.getLimbs()));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long multiply(LimbRegion output, LimbRegion left, LimbRegion right) {
        ordered(left, right); output.requireOutput(left.getLimbs() + right.getLimbs());
        disjoint(output, left); disjoint(output, right);
        preflight(output); preflight(left); preflight(right);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(left, scope); var second = snapshot(right, scope);
            var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(multiply, destination, first, left.getLimbs(), second, right.getLimbs()));
            copyFrom(output, destination); return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long divideWord(LimbRegion output, long fractionalLimbs, LimbRegion input, long divisor) {
        nonzero(divisor);
        if (fractionalLimbs < 0 || fractionalLimbs > Integer.MAX_VALUE / 8L) throw fault("Invalid fractional limb count");
        output.requireOutput(input.getLimbs() + fractionalLimbs); exactAlias(output, input);
        preflight(output); preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var source = snapshot(input, scope); var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(divideWord, destination, fractionalLimbs, source, input.getLimbs(), divisor));
            copyFrom(output, destination); return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long moduloWord(LimbRegion input, long divisor) {
        nonzero(divisor);
        preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            return interop.asLong(interop.execute(moduloWord, snapshot(input, scope), input.getLimbs(), divisor));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long shiftRight(LimbRegion output, LimbRegion input, long count, boolean negative) {
        input.requirePositive();
        if (count <= 0 || count >= input.getLimbs() * 64) throw fault("Limb right shift must be inside the input width");
        output.requireOutput(input.getLimbs() - (negative ? count - 1 : count) / 64); exactAlias(output, input);
        preflight(output); preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var source = snapshot(input, scope); var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(shiftRight, destination, source, input.getLimbs(), count, negative ? 1 : 0));
            copyFrom(output, destination); return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public double toDouble(LimbRegion input, boolean negative, long exponent) {
        preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            return interop.asDouble(interop.execute(getDouble, snapshot(input, scope), negative ? -input.getLimbs() : input.getLimbs(), exponent));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long gcdWords(long left, long right) {

        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            return interop.asLong(interop.execute(gcdWords, left, right));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long gcdWord(LimbRegion input, long word) {
        input.requirePositive();
        if (input.getLimbs() > 1 && word == 0) throw fault("Multi-limb GCD requires a nonzero word");
        preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var source = snapshot(input, scope);
            if (input.getLimbs() > 1) normalized(input, source);
            return interop.asLong(interop.execute(gcdWord, source, input.getLimbs(), word));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long gcd(LimbRegion output, LimbRegion left, LimbRegion right) {
        ordered(left, right); output.requireOutput(right.getLimbs()); exactAlias(output, left); exactAlias(output, right);
        preflight(output); preflight(left); preflight(right);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(left, scope); var second = snapshot(right, scope);
            if (left.getLimbs() > 1) normalized(left, first);
            if (right.getLimbs() > 1) normalized(right, second);
            else if (left.getLimbs() > 1 && second.readWord(0) == 0) throw fault("Multi-limb GCD requires a nonzero divisor");
            var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(gcd, destination, first, left.getLimbs(), second, right.getLimbs()));
            // Copy only the returned prefix; unused limbs and canaries stay intact.
            copyFrom(output, destination, result); return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long shiftLeft(LimbRegion output, LimbRegion input, long count) {
        input.requirePositive();
        if (count <= 0 || count > Integer.MAX_VALUE * 8L) throw fault("Invalid limb left shift count");
        output.requireOutput(input.getLimbs() + (count + 63) / 64); exactAlias(output, input);
        preflight(output); preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var source = snapshot(input, scope); var destination = destination(output, scope);
            long result = interop.asLong(interop.execute(shiftLeft, destination, source, input.getLimbs(), count));
            copyFrom(output, destination); return result;
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public void bitwise(LimbRegion output, LimbRegion left, LimbRegion right, LimbBitwise operation) {
        ordered(left, right);
        if (left.getLimbs() != right.getLimbs()) throw fault("Logical limb operations require equal counts");
        output.requireOutput(left.getLimbs()); exactAlias(output, left); exactAlias(output, right);
        Object function = switch (operation) { case AND -> and; case AND_NOT -> andNot; case OR -> or; case XOR -> xor; };
        preflight(output); preflight(left); preflight(right);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(left, scope); var second = snapshot(right, scope);
            var destination = destination(output, scope);
            interop.execute(function, destination, first, second, left.getLimbs()); copyFrom(output, destination);
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public long populationCount(LimbRegion input) {
        input.requirePositive();
        preflight(input);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            return interop.asLong(interop.execute(populationCount, snapshot(input, scope), input.getLimbs()));
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public void divide(LimbRegion quotient, LimbRegion remainder, long fractionalLimbs, LimbRegion numerator, LimbRegion divisor) {
        if (fractionalLimbs != 0) throw fault("Multi-limb division requires zero fractional limbs");
        divisionInputs(numerator, divisor); quotient.requireOutput(numerator.getLimbs() - divisor.getLimbs() + 1);
        remainder.requireOutput(divisor.getLimbs());
        disjoint(quotient, remainder); disjoint(quotient, numerator); disjoint(quotient, divisor);
        disjoint(remainder, divisor); exactAlias(remainder, numerator);
        preflight(quotient); preflight(remainder); preflight(numerator); preflight(divisor);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(numerator, scope); var second = snapshot(divisor, scope);
            normalized(divisor, second);
            var q = destination(quotient, scope); var r = destination(remainder, scope);
            interop.execute(divide, q, r, fractionalLimbs, first, numerator.getLimbs(), second, divisor.getLimbs());
            copyFrom(quotient, q); copyFrom(remainder, r);
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public void quotient(LimbRegion output, LimbRegion numerator, LimbRegion divisor) {
        divisionInputs(numerator, divisor);
        output.requireOutput(numerator.getLimbs() - divisor.getLimbs() + 1);
        disjoint(output, numerator); disjoint(output, divisor);
        preflight(output); preflight(numerator); preflight(divisor);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(numerator, scope); var second = snapshot(divisor, scope);
            normalized(divisor, second);
            var destination = destination(output, scope);
            var scratch = scope.allocate(divisor.getByteSize());
            interop.execute(quotient, destination, scratch, first, numerator.getLimbs(), second, divisor.getLimbs());
            copyFrom(output, destination);
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }

    @TruffleBoundary @Override public void remainder(LimbRegion output, LimbRegion numerator, LimbRegion divisor) {
        divisionInputs(numerator, divisor);
        output.requireOutput(divisor.getLimbs());
        disjoint(output, divisor); exactAlias(output, numerator);
        preflight(output); preflight(numerator); preflight(divisor);
        var threads = thc.Language.currentState(null).getThreads();
        var previous = threads.enterForeign(ForeignSafety.UNSAFE);
        try (var scope = new NativeLimbScope()) {
            var first = snapshot(numerator, scope); var second = snapshot(divisor, scope);
            normalized(divisor, second);
            var destination = destination(output, scope);
            var scratch = scope.allocate((numerator.getLimbs() - divisor.getLimbs() + 1) * 8);
            interop.execute(remainder, destination, scratch, first, numerator.getLimbs(), second, divisor.getLimbs());
            copyFrom(output, destination);
        } catch (Exception failure) { throw rethrow(failure); }
        finally { threads.leaveForeign(previous); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }
}
