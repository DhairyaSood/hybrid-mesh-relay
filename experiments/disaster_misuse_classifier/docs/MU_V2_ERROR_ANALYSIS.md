# MU-v2 misuse error analysis (civic challenges)

Generated: 2026-10-23T13:09:42.559640+00:00

Evidence from saved prediction files. Explanations mix **observed** label pairs with **hypotheses** (marked).
Not real-world performance. MU-v1 remains canonical.

## Civic challenge v2

- Predictions: `/Users/purusharth/Desktop/hybrid-mesh-relay/experiments/disaster_misuse_classifier/outputs/civic_challenge_v2_eval_20261023T130316Z/predictions_mu_v2.jsonl`
- Errors: **21/73** | Benign FP: **12** | Harmful FN: **2**
- By language: {'english': 9, 'devanagari_hindi': 6, 'latin_hinglish': 6}

### Confusion pairs (v2)

- benign_emergency_information -> coercion_or_extortion: 6
- benign_emergency_information -> quoted_or_historical_threat: 2
- benign_emergency_information -> ambiguous_needs_review: 2
- quoted_or_historical_threat -> coercion_or_extortion: 1
- report_or_warning_about_misconduct -> benign_emergency_information: 1
- ambiguous_needs_review -> report_or_warning_about_misconduct: 1
- report_or_warning_about_misconduct -> possible_relief_worker_impersonation_or_fraud: 1
- coercion_or_extortion -> ambiguous_needs_review: 1
- benign_emergency_information -> direct_threat_or_intimidation: 1
- benign_emergency_information -> possible_relief_worker_impersonation_or_fraud: 1
- quoted_or_historical_threat -> possible_relief_worker_impersonation_or_fraud: 1
- report_or_warning_about_misconduct -> ambiguous_needs_review: 1
- ambiguous_needs_review -> benign_emergency_information: 1
- possible_relief_worker_impersonation_or_fraud -> coercion_or_extortion: 1

### Every MU-v2 misclassification (v2)

#### `CH2-EN-04` (english)
- **Text:** Police escorted the water tanker through—thank you, that was urgent for block C.
- **Expected:** `benign_emergency_information` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.2438482320637123), ('quoted_or_historical_threat', 0.1720075282688757), ('benign_emergency_information', 0.13742404622667945)]
- Possible keyword effect: trigger word 'police'
- Possible keyword effect: trigger word 'urgent'

#### `CH2-EN-06` (english)
- **Text:** We're collecting money for generator fuel at the lane committee—optional, receipt notebook at table 2.
- **Expected:** `benign_emergency_information` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.3633346584599596), ('benign_emergency_information', 0.16421743737717545), ('direct_threat_or_intimidation', 0.1137099969695585)]
- Possible keyword effect: trigger word 'money'

#### `CH2-EN-07` (english)
- **Text:** Share-auto stand moved to cinema junction because of rubble on old lane—helpful if you're heading to the clinic.
- **Expected:** `benign_emergency_information` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.27733602029279675), ('possible_relief_worker_impersonation_or_fraud', 0.22197588493540102), ('benign_emergency_information', 0.19326060292419192)]
- Possible keyword effect: trigger word 'help'

#### `CH2-EN-09` (english)
- **Text:** Volunteer boat team needs 2 strong arms at slipway 3 at dawn—food provided, no pay required.
- **Expected:** `benign_emergency_information` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.3680483779364465), ('possible_relief_worker_impersonation_or_fraud', 0.2023654606059859), ('benign_emergency_information', 0.1929422154110065)]
- Possible keyword effect: trigger word 'pay'

#### `CH2-EN-10` (english)
- **Text:** This humidity is murder on the toddlers—any spare ORS packets at the creche corner?
- **Expected:** `benign_emergency_information` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.3322299291186674), ('benign_emergency_information', 0.25663269378845255), ('report_or_warning_about_misconduct', 0.11497827973440158)]
- Fragmentary/question form may increase ambiguous/harmful confusion (hypothesis).

#### `CH2-EN-15` (english)
- **Text:** Pay what you can at the kitchen if you want extra rice—zero is fine for tonight.
- **Expected:** `benign_emergency_information` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.5268362713055674), ('report_or_warning_about_misconduct', 0.11838632953087098), ('direct_threat_or_intimidation', 0.11191061408277846)]
- Possible keyword effect: trigger word 'pay'

#### `CH2-EN-17` (english)
- **Text:** She told me he yelled 'I'll break your legs if you queue here' by the pump—repeating her words for the logbook.
- **Expected:** `quoted_or_historical_threat` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.4558027735236522), ('direct_threat_or_intimidation', 0.19709273501187502), ('benign_emergency_information', 0.11436154378723035)]
- Quote/report vs direct-threat boundary: model may treat quoted violent wording as sender intent (hypothesis).

#### `CH2-EN-18` (english)
- **Text:** That viral voice note claiming the dam burst is old audio from 2019—please stop forwarding.
- **Expected:** `report_or_warning_about_misconduct` → **Predicted:** `benign_emergency_information`
- **Top scores:** [('benign_emergency_information', 0.2806254157962165), ('ambiguous_needs_review', 0.2045618192633281), ('report_or_warning_about_misconduct', 0.1918073943225712)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HI-01` (devanagari_hindi)
- **Text:** टीचर ने लिखा: बच्चों को ४ बजे पूर्वी गेट से ले जाएँ, अभ्यास खत्म—धमकी नहीं, सूचना है।
- **Expected:** `benign_emergency_information` → **Predicted:** `quoted_or_historical_threat`
- **Top scores:** [('quoted_or_historical_threat', 0.4487282569030344), ('benign_emergency_information', 0.15607520935911473), ('report_or_warning_about_misconduct', 0.12781892711114667)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HI-08` (devanagari_hindi)
- **Text:** फॉरवर्ड किया गया 'जिलाधिकारी' नोटिस—लेटरहेड अजीब लग रहा, क्या कोई लैंडलाइन से पुष्टि करे?
- **Expected:** `ambiguous_needs_review` → **Predicted:** `report_or_warning_about_misconduct`
- **Top scores:** [('report_or_warning_about_misconduct', 0.5359628780251174), ('benign_emergency_information', 0.17617445971373882), ('direct_threat_or_intimidation', 0.10268790838191783)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HI-11` (devanagari_hindi)
- **Text:** वायरल मैसेज कि बांध टूटा है पुराना है—कृपया दोबारा न भेजें।
- **Expected:** `report_or_warning_about_misconduct` → **Predicted:** `possible_relief_worker_impersonation_or_fraud`
- **Top scores:** [('possible_relief_worker_impersonation_or_fraud', 0.2763046144331565), ('benign_emergency_information', 0.2729624813776437), ('ambiguous_needs_review', 0.10661360695679613)]
- Quote/report vs direct-threat boundary: model may treat quoted violent wording as sender intent (hypothesis).

#### `CH2-HI-13` (devanagari_hindi)
- **Text:** अभी ५०० रुपये दो नहीं तो आज रात खाने की लाइन में नहीं लगने दूँगा।
- **Expected:** `coercion_or_extortion` → **Predicted:** `ambiguous_needs_review`
- **Top scores:** [('ambiguous_needs_review', 0.2615872140999944), ('benign_emergency_information', 0.22709954627437445), ('quoted_or_historical_threat', 0.14401822841834963)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HI-17` (devanagari_hindi)
- **Text:** यह गर्मी बच्चों पर भारी पड़ रही है—कोई पंखा उधार दे सकता है?
- **Expected:** `benign_emergency_information` → **Predicted:** `direct_threat_or_intimidation`
- **Top scores:** [('direct_threat_or_intimidation', 0.3630258434946302), ('report_or_warning_about_misconduct', 0.18224416959325027), ('benign_emergency_information', 0.15970522716636232)]
- Fragmentary/question form may increase ambiguous/harmful confusion (hypothesis).

#### `CH2-HG-01` (latin_hinglish)
- **Text:** help chahiye school side—gate band hai kya? daughter inside, bas puch raha hun
- **Expected:** `benign_emergency_information` → **Predicted:** `ambiguous_needs_review`
- **Top scores:** [('ambiguous_needs_review', 0.4217237140649908), ('benign_emergency_information', 0.27534858404571777), ('report_or_warning_about_misconduct', 0.0943018248146798)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HG-05` (latin_hinglish)
- **Text:** jitna ho sake donate karo generator ke liye—optional hai, force nahi
- **Expected:** `benign_emergency_information` → **Predicted:** `possible_relief_worker_impersonation_or_fraud`
- **Top scores:** [('possible_relief_worker_impersonation_or_fraud', 0.21281500390791389), ('report_or_warning_about_misconduct', 0.2086368159935225), ('benign_emergency_information', 0.18464741990207065)]
- Possible keyword effect: donation/payment wording

#### `CH2-HG-10` (latin_hinglish)
- **Text:** usne bola 'tum bahar nikle to dekh lunga'—main repeat kar raha hu statement ke liye
- **Expected:** `quoted_or_historical_threat` → **Predicted:** `possible_relief_worker_impersonation_or_fraud`
- **Top scores:** [('possible_relief_worker_impersonation_or_fraud', 0.43773126396827344), ('quoted_or_historical_threat', 0.16289986043901747), ('benign_emergency_information', 0.12397255758891933)]
- Quote/report vs direct-threat boundary: model may treat quoted violent wording as sender intent (hypothesis).

#### `CH2-HG-11` (latin_hinglish)
- **Text:** wo evacuation voice note purana hai 2020 ka—please mat forward karo
- **Expected:** `report_or_warning_about_misconduct` → **Predicted:** `ambiguous_needs_review`
- **Top scores:** [('ambiguous_needs_review', 0.3555181467326906), ('benign_emergency_information', 0.25002061262633857), ('report_or_warning_about_misconduct', 0.1683315117691769)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HG-17` (latin_hinglish)
- **Text:** yaar yeh line killing hai—koi ORS hai kya bacche ke liye?
- **Expected:** `benign_emergency_information` → **Predicted:** `ambiguous_needs_review`
- **Top scores:** [('ambiguous_needs_review', 0.29748726895267286), ('possible_relief_worker_impersonation_or_fraud', 0.16741333037620248), ('benign_emergency_information', 0.1503141789313695)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HG-20` (latin_hinglish)
- **Text:** looks like municipal SMS: boil water till 6pm—sender id short code weird though?
- **Expected:** `ambiguous_needs_review` → **Predicted:** `benign_emergency_information`
- **Top scores:** [('benign_emergency_information', 0.425499325982372), ('ambiguous_needs_review', 0.2133773691498273), ('possible_relief_worker_impersonation_or_fraud', 0.1065902157068863)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-HI-20` (devanagari_hindi)
- **Text:** इंसुलिन के लिए कुछ रुपये उधार चाहिए शुक्रवार तक—धमकी नहीं, संपर्क करें।
- **Expected:** `benign_emergency_information` → **Predicted:** `quoted_or_historical_threat`
- **Top scores:** [('quoted_or_historical_threat', 0.25878913275146204), ('report_or_warning_about_misconduct', 0.2527117669408602), ('benign_emergency_information', 0.1398277197658203)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

#### `CH2-EN-30` (english)
- **Text:** Central relief desk here—your name flagged unless you immediate transfer clearance fee.
- **Expected:** `possible_relief_worker_impersonation_or_fraud` → **Predicted:** `coercion_or_extortion`
- **Top scores:** [('coercion_or_extortion', 0.416018026999974), ('possible_relief_worker_impersonation_or_fraud', 0.22175362962942102), ('benign_emergency_information', 0.11491584153954312)]
- No single-keyword explanation; may involve context or training gap (hypothesis).

## Civic challenge v1 (MU-v2, diagnostic)

- Errors: **50/73** | Benign FP: **34** | Harmful FN: **6**
