// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

/** Algebraic log1p forms avoid cancellation near zero/one. Above 2^28,
 * the correction to log(2*x) is below one binary64 ulp; below 2^-28,
 * asinh/atanh round to x. No intermediate squares can overflow. */
final class InverseHyperbolic {
    private static final double LN2 = 0.6931471805599453;

    private InverseHyperbolic() {}

    static double asinh(double x) {
        double a = Math.abs(x);
        if (a < 3.725290298461914e-9 || !Double.isFinite(a)) return x;
        double result = a > 268435456.0 ? Math.log(a) + LN2
                : Math.log1p(a + a * a / (1.0 + Math.sqrt(1.0 + a * a)));
        return Math.copySign(result, x);
    }

    static double acosh(double x) {
        if (x < 1.0) return Double.NaN;
        if (x > 268435456.0) return Math.log(x) + LN2;
        double t = x - 1.0;
        return Math.log1p(t + Math.sqrt(t * (x + 1.0)));
    }

    static double atanh(double x) {
        double a = Math.abs(x);
        if (a < 3.725290298461914e-9) return x;
        if (a > 1.0) return Double.NaN;
        return Math.copySign(0.5 * Math.log1p(2.0 * a / (1.0 - a)), x);
    }
}
