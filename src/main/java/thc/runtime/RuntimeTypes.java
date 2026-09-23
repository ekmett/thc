package thc.runtime;

import com.oracle.truffle.api.dsl.TypeCast;
import com.oracle.truffle.api.dsl.TypeCheck;
import com.oracle.truffle.api.dsl.TypeSystem;
import kotlin.Unit;

/**
 * Runtime categories used by typed AST execution. Guest Int#/Word#/Char# retain
 * their 64-bit payload; boxed constructors and unevaluated thunks stay distinct.
 * Numeric widening belongs to the particular guest operation, not a global cast.
 */
@TypeSystem({
        long.class,
        float.class,
        double.class,
        boolean.class,
        Closure.class,
        DataValue.class,
        LiteralAddress.class,
        Thunk.class,
        Unit.class
})
public abstract class RuntimeTypes {
    @TypeCheck(Unit.class)
    public static boolean isUnit(Object value) {
        return value == Unit.INSTANCE;
    }

    @TypeCast(Unit.class)
    public static Unit asUnit(Object value) {
        assert value == Unit.INSTANCE;
        return Unit.INSTANCE;
    }
}
