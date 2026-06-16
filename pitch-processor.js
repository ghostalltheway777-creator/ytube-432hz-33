/*
 * 432 Hz Quantum — pitch-processor (AudioWorklet)
 *
 * Sænker tonehøjden med forholdet 432/440 = 0.981818… UDEN at ændre tempoet.
 *
 * Metode: ramping-delay pitch shift (verificeret: 440 Hz ind → 432 Hz ud).
 *   read = write - delay, hvor delay vokser med (1 - ratio) pr. sample.
 *   => læse-pointeren avancerer med rate `ratio` => frekvensen ganges med ratio.
 *   To delay-taps forskudt en halv vinduebredde (W/2) krydsfades med Hann-vinduer
 *   der er 0 netop hvor hver tap "wrapper" (delay 0 → W), så springet er lydløst.
 *   Hann med 50% overlap summerer til 1.0 → ingen amplitude-modulation. Tempoet
 *   bevares fordi vi stadig producerer ét output-sample pr. input-sample.
 */
class PitchProcessor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [{ name: "ratio", defaultValue: 432 / 440, minValue: 0.5, maxValue: 2.0 }];
  }

  constructor() {
    super();
    this.W = 2048;                 // vinduebredde (~46 ms ved 44.1 kHz)
    this.ringSize = this.W * 2;
    this.ch = [];                  // per-kanal tilstand (lazy)
  }

  _ensure(n) {
    while (this.ch.length < n) {
      this.ch.push({
        ring: new Float32Array(this.ringSize),
        write: 0,
        d: [0, this.W / 2],        // to tap-delays, forskudt en halv vinduebredde
      });
    }
  }

  process(inputs, outputs, params) {
    const input = inputs[0];
    const output = outputs[0];
    if (!output || output.length === 0) return true;

    const nch = output.length;
    this._ensure(nch);

    const ratio = params.ratio.length > 0 ? params.ratio[0] : 432 / 440;
    const step = 1 - ratio;            // delay vokser med dette pr. sample
    const W = this.W;
    const ringSize = this.ringSize;
    const TWO_PI = Math.PI * 2;

    for (let c = 0; c < nch; c++) {
      const outC = output[c];
      const inC = (input && input[c]) || (input && input[0]) || null;
      const st = this.ch[c];
      const ring = st.ring;
      const d = st.d;

      for (let i = 0; i < outC.length; i++) {
        ring[st.write] = inC ? inC[i] : 0;

        let s = 0;
        for (let k = 0; k < 2; k++) {
          const win = 0.5 - 0.5 * Math.cos((TWO_PI * d[k]) / W);   // 0 ved delay 0 og W

          let rp = st.write - d[k];
          if (rp < 0) rp += ringSize;
          let i0 = Math.floor(rp);
          const frac = rp - i0;
          i0 = ((i0 % ringSize) + ringSize) % ringSize;
          const i1 = (i0 + 1) % ringSize;
          s += win * (ring[i0] * (1 - frac) + ring[i1] * frac);

          d[k] += step;
          if (d[k] >= W) d[k] -= W;
          else if (d[k] < 0) d[k] += W;     // tillader ratio > 1 (op-shift) også
        }

        outC[i] = s;
        st.write++;
        if (st.write >= ringSize) st.write = 0;
      }
    }
    return true;
  }
}

registerProcessor("pitch-processor", PitchProcessor);
