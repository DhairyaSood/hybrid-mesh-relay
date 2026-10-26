# MobileBERT / pretrained classifier assessment (Neyra)

**Conclusion:** Stay on **TF-IDF + Logistic Regression** for current offline prototypes unless a labeled multilingual fine-tune set and on-device latency budget are approved.

| Criterion | MobileBERT-class model | Current compact LR |
|-----------|------------------------|-------------------|
| Hindi / Devanagari | Needs multilingual variant (mBERT/MuRIL); MobileBERT is English-centric | Bag-of-words handles mixed script in practice but weak on morphology |
| Training data | No licensed in-repo corpus for 7 misuse / 17 hazard labels at scale | Synthetic + India–Nepal tweets (disaster) exist |
| Model size | Tens of MB even quantized; tokenizer asset | ~300–650 KB joblibs |
| Runtime | ONNX/TFLite + tokenizer on mid Android | sklearn/joblib or exported sparse weights |
| Licence | Check Google model licence for redistribution | Internal sklearn pipeline |
| Eval parity | Would need **identical** civic v2 + disaster holdouts | Baselines documented |

**Recommendation:** Candidate only for a future A/B after independent challenge sets and device profiling. Do not replace MU-v1/DC-T2 on synthetic val F1 alone.
