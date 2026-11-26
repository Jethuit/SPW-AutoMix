package automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Reuses work buffers and twiddle factors across frames of the same size. */
internal class DspFft(private val size: Int) {
    init {
        require(size >= 2 && (size and (size - 1)) == 0)
    }

    val real = DoubleArray(size)
    val imaginary = DoubleArray(size)
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - Integer.numberOfTrailingZeros(size)) }
    private val cosine = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sine = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }

    fun windowed(samples: FloatArray, start: Int, window: DoubleArray) {
        val length = minOf(samples.size - start, window.size)
        for (i in 0 until length) real[i] = samples[start + i] * window[i]
        real.fill(0.0, length)
        imaginary.fill(0.0)
        transform()
    }

    fun transform(inverse: Boolean = false) {
        for (index in 0 until size) {
            val swap = reversed[index]
            if (swap > index) {
                val realValue = real[index]
                val imaginaryValue = imaginary[index]
                real[index] = real[swap]
                imaginary[index] = imaginary[swap]
                real[swap] = realValue
                imaginary[swap] = imaginaryValue
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var base = 0
            while (base < size) {
                for (offset in 0 until half) {
                    val twiddle = offset * step
                    val a = base + offset
                    val b = a + half
                    val cos = cosine[twiddle]
                    val sin = if (inverse) -sine[twiddle] else sine[twiddle]
                    val r = real[b] * cos - imaginary[b] * sin
                    val i = real[b] * sin + imaginary[b] * cos
                    real[b] = real[a] - r
                    imaginary[b] = imaginary[a] - i
                    real[a] += r
                    imaginary[a] += i
                }
                base += 2 * half
            }
            half *= 2
        }
        if (inverse) {
            for (i in 0 until size) {
                real[i] /= size.toDouble()
                imaginary[i] /= size.toDouble()
            }
        }
    }
}
