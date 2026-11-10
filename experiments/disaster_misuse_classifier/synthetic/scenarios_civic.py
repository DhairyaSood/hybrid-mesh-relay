"""Civic / traffic / official-advisory scenario families (v2 expansion).

Synthetic only — labels reflect authored scenario intent, not sender authentication.
"""

from __future__ import annotations

from synthetic.scenarios import (
    PLACES_EN,
    PLACES_HI,
    PLACES_HINGLISH,
    TemplateFamily,
    _pick,
)

GROUP_TRAFFIC = "civic_A_traffic_transport"
GROUP_OFFICIAL = "civic_B_official_advisories"
GROUP_IMPERSONATION = "civic_C_impersonation_fraud"
GROUP_BENIGN_COORD = "civic_D_benign_coordination"
GROUP_REPORT_QUOTE = "civic_E_reports_quotations"
GROUP_ROUTINE = "civic_F_routine_public"

ROUTES_EN = [
    "NH-48 service road",
    "Ring Road eastbound",
    "Old bridge approach",
    "Station Road flyover",
    "Bypass near sector 12",
]
ROUTES_HI = ["एनएच-48 सर्विस रोड", "रिंग रोड पूर्व", "पुराना पुल मार्ग", "स्टेशन रोड फ्लाईओवर"]
ROUTES_HING = ["NH-48 service road", "ring road east", "old bridge approach", "station road"]

AUTHORITIES_EN = [
    "District disaster management cell",
    "Municipal corporation control room",
    "Traffic police district HQ",
    "Fire and rescue services",
    "Public health department",
    "Railway divisional office",
    "State electricity board",
]
AUTHORITIES_HI = [
    "जिला आपदा प्रबंधन कक्ष",
    "नगर निगम नियंत्रण कक्ष",
    "यातायात पुलिस जिला मुख्यालय",
    "अग्निशमन एवं बचाव सेवा",
]
AUTHORITIES_HING = [
    "district disaster cell",
    "municipal control room",
    "traffic police HQ",
    "fire rescue dept",
]


def _fmt_en(tpl: str, rng) -> str:
    return tpl.format(
        place=_pick(rng, PLACES_EN),
        route=_pick(rng, ROUTES_EN),
        auth=_pick(rng, AUTHORITIES_EN),
        time=_pick(rng, ["06:00", "14:30", "18:45", "22:15"]),
        delay=_pick(rng, ["30", "45", "90", "120"]),
    )


def _fmt_hi(tpl: str, rng) -> str:
    return tpl.format(
        place=_pick(rng, PLACES_HI),
        route=_pick(rng, ROUTES_HI),
        auth=_pick(rng, AUTHORITIES_HI),
        time=_pick(rng, ["06:00", "14:30", "18:45"]),
        delay=_pick(rng, ["30", "45", "90", "120"]),
    )


def _fmt_hing(tpl: str, rng) -> str:
    return tpl.format(
        place=_pick(rng, PLACES_HINGLISH),
        route=_pick(rng, ROUTES_HING),
        auth=_pick(rng, AUTHORITIES_HING),
        time=_pick(rng, ["6 baje", "2:30 pm", "raat 10"]),
        delay=_pick(rng, ["30", "45", "90", "120"]),
    )


def _bundle(
    group: str,
    label: str,
    prefix: str,
    en_tpls: list[str],
    hi_tpls: list[str],
    hing_tpls: list[str],
    n_per: int = 10,
) -> list[TemplateFamily]:
    out: list[TemplateFamily] = []
    for i, tpl in enumerate(en_tpls):
        for v in range(n_per):
            fid = f"{prefix}_en_{i}_{v}"
            out.append(
                TemplateFamily(
                    family_id=fid,
                    label=label,
                    language_bucket="english",
                    language_subtype="english",
                    disaster_tags=("civic",),
                    scenario_group=group,
                    render=lambda rng, _t=tpl: _fmt_en(_t, rng),
                )
            )
    for i, tpl in enumerate(hi_tpls):
        for v in range(n_per):
            out.append(
                TemplateFamily(
                    family_id=f"{prefix}_hi_{i}_{v}",
                    label=label,
                    language_bucket="hindi_hinglish",
                    language_subtype="devanagari_hindi",
                    disaster_tags=("civic",),
                    scenario_group=group,
                    render=lambda rng, _t=tpl: _fmt_hi(_t, rng),
                )
            )
    for i, tpl in enumerate(hing_tpls):
        for v in range(n_per):
            out.append(
                TemplateFamily(
                    family_id=f"{prefix}_hing_{i}_{v}",
                    label=label,
                    language_bucket="hindi_hinglish",
                    language_subtype="latin_hinglish",
                    disaster_tags=("civic",),
                    scenario_group=group,
                    render=lambda rng, _t=tpl: _fmt_hing(_t, rng),
                )
            )
    return out


def _traffic_benign() -> list[TemplateFamily]:
    en = [
        "{auth}: {route} closed near {place} due to waterlogging. Use alternate via bypass. Expect {delay} min delay.",
        "Traffic update: landslide debris cleared on {route}; one lane open. Drive slowly near {place}.",
        "Railway announcement: trains to Riverside delayed {delay} minutes after track inspection near {place}.",
        "Bus operator: Route 14 diverted via {route} until {time}. Temporary stop at {place} community hall.",
        "Ambulance corridor active on {route} until {time}. Other vehicles please yield near {place}.",
        "Please avoid {route} if possible — long queue after accident near {place}. Verified by local traffic desk.",
    ]
    hi = [
        "{auth}: {route} पर {place} के पास जलभराव — बाईपास से जाएँ। लगभग {delay} मिनट देरी।",
        "रेलवे सूचना: {place} के पास निरीक्षण के बाद ट्रेनों में {delay} मिनट की देरी।",
        "एम्बुलेंस मार्ग {route} पर {time} तक — अन्य वाहन रास्ता दें, {place} के निकट।",
    ]
    hing = [
        "{auth} bol raha hai: {route} band hai {place} ke paas flooding — bypass use karo, {delay} min delay.",
        "bus route 14 divert via {route} till {time}, temp stop {place} hall.",
        "ambulance corridor on {route} till {time}, please side do near {place}.",
    ]
    return _bundle(GROUP_TRAFFIC, "benign_emergency_information", "civ_traf_ok", en, hi, hing, 8)


def _traffic_report() -> list[TemplateFamily]:
    en = [
        "Heads-up: rumour that {route} is closed to push people toward a paid 'shortcut' near {place} — official desk says road is open.",
        "Warning: fake WhatsApp traffic map asking UPI for 'verified diversion' at {place}. Report to cyber cell; do not pay.",
        "Someone is spreading false bridge closure at {place}; {auth} confirmed normal traffic on {route}.",
    ]
    hi = [
        "चेतावनी: {place} पर झूठा मैसेज कि {route} बंद है ताकि लोग फीस वाले 'शॉर्टकट' पर भेजे जाएँ — अधिकारिक सूचना: रास्ता खुला है।",
        "फर्जी ट्रैफिक अपडेट के लिए UPI मांगा जा रहा है {place} के पास — भुगतान न करें, रिपोर्ट करें।",
    ]
    hing = [
        "sun lo: fake msg {route} band hai {place} pe — actually open hai per traffic desk, scam ho sakta hai.",
        "warning: koi UPI maang raha 'verified diversion' ke liye near {place} — pay mat karo.",
    ]
    return _bundle(GROUP_TRAFFIC, "report_or_warning_about_misconduct", "civ_traf_rep", en, hi, hing, 8)


def _traffic_fraud_coerce() -> list[TemplateFamily]:
    fraud = _bundle(
        GROUP_TRAFFIC,
        "possible_relief_worker_impersonation_or_fraud",
        "civ_traf_fraud",
        [
            "'Traffic police fine portal': pay Rs 499 on this link to remove block on {route} near {place} within 1 hour.",
            "Impersonated toll desk: send OTP to unlock passage at {place} checkpoint on {route}.",
        ],
        [
            "'ट्रैफिक जुर्माना' लिंक: {place} पर {route} ब्लॉक हटाने के लिए 499 रुपये भेजें — तत्काल।",
        ],
        [
            "fake traffic fine link — pay 499 to clear block {route} near {place}, urgent bola.",
        ],
        8,
    )
    coerce = _bundle(
        GROUP_TRAFFIC,
        "coercion_or_extortion",
        "civ_traf_coerce",
        [
            "No entry on {route} unless you pay cash at the unofficial barricade near {place}. Not negotiable.",
            "Pay 200 at the makeshift checkpoint by {place} or wait at the back of the queue on {route}.",
        ],
        [
            "{place} के पास अनाधिकृत बैरिकेड — {route} पर जाने के लिए नकद दो, वरना इंतजार करो।",
        ],
        [
            "{route} pe aage jaane ke liye 200 do {place} checkpoint pe warna last me rukna.",
        ],
        8,
    )
    amb = _bundle(
        GROUP_TRAFFIC,
        "ambiguous_needs_review",
        "civ_traf_amb",
        [
            "Heard {route} closed near {place}? cousin forwarded voice note, not sure if official.",
            "Traffic jam huge on {route} — someone said police charging money but I didn't see myself.",
        ],
        [
            "सुना {route} बंद है {place} के पास? वॉइस नोट मिला, पक्का नहीं अधिकारिक है या नहीं।",
        ],
        [
            "{route} band hai kya near {place}? forward hua msg, verify nahi kiya.",
        ],
        7,
    )
    return fraud + coerce + amb


def _official_benign() -> list[TemplateFamily]:
    en = [
        "{auth}: Evacuation advisory for low-lying wards near {place}. Proceed to listed shelter by {time}. Carry ID and medicines.",
        "Municipal notice: schools closed tomorrow in sector around {place} due to weather. Online classes where possible.",
        "Public health: heatwave precautions — drink water, avoid midday travel on {route}. Helpdesk at {place}.",
        "Utility alert: planned power maintenance {time}–{delay} minutes in blocks near {place}. Not an emergency outage.",
        "Relief registration at {place} community centre — free, no fee. Bring household ID only; ignore paid 'priority' offers.",
        "Curfew relaxation {time}–{delay} for essential travel on {route}. Follow {auth} radio bulletin.",
    ]
    hi = [
        "{auth}: {place} के निचले इलाकों के लिए निकासी सलाह — {time} तक शरण केंद्र जाएँ, दवाइयाँ साथ रखें।",
        "नगर निगम: {place} क्षेत्र में कल स्कूल बंद — मौसम चेतावनी।",
        "राहत पंजीकरण {place} पर — निःशुल्क, कोई फीस नहीं; केवल पहचान पत्र लाएँ।",
    ]
    hing = [
        "{auth} notice: evacuation low area near {place} by {time}, shelter me jao with ID.",
        "school band kal {place} sector — weather alert, online class hoga.",
        "relief registration free at {place} — fee wale msg ignore karo.",
    ]
    return _bundle(GROUP_OFFICIAL, "benign_emergency_information", "civ_off_ok", en, hi, hing, 9)


def _official_impersonation() -> list[TemplateFamily]:
    return _bundle(
        GROUP_IMPERSONATION,
        "possible_relief_worker_impersonation_or_fraud",
        "civ_off_fake",
        [
            "GOVT COMPENSATION SCHEME: transfer Rs 5000 'processing fee' to receive flood grant for {place} address.",
            "Fake {auth} message: share Aadhaar OTP and bank PIN to confirm relief slot at {place}.",
            "Forged school closure PDF — click link to pay 'security refund' for students at {place}.",
            "Impersonated relief worker: pay for 'free' ration kit delivery to {place} lane — UPI within 30 minutes.",
        ],
        [
            "नकली सरकारी योजना: {place} के लिए 'प्रोसेसिंग फीस' भेजें वरना मुआवजा रोक दिया जाएगा।",
            "फर्जी {auth} संदेश: राहत स्लॉट के लिए OTP और बैंक विवरण भेजें — {place}।",
        ],
        [
            "fake govt compensation — processing fee bhejo {place} ke liye grant milega bola.",
            "relief worker bolke free ration ke paise maang rahe {place} lane — UPI now.",
        ],
        9,
    )


def _official_coercion() -> list[TemplateFamily]:
    return _bundle(
        GROUP_IMPERSONATION,
        "coercion_or_extortion",
        "civ_off_coerce",
        [
            "Police notice (unverified): pay fine on this number today or face arrest when collecting supplies at {place}.",
            "Pay 1000 now or we lock you out of the night shelter queue at {place} — final warning.",
        ],
        [
            "नकली पुलिस नोटिस: आज भुगतान नहीं तो {place} पर राशन लेने पर गिरफ्तारी की धमकी।",
        ],
        [
            "fake police msg — pay 1000 warna {place} shelter queue se nikal denge.",
        ],
        8,
    )


def _benign_coordination() -> list[TemplateFamily]:
    en = [
        "Help needed in school: {place} staff asking parents to pick up day students by {time} after smoke alarm test.",
        "Looking for my uncle last seen near {route} after evacuation — welfare check, not accusing anyone.",
        "Need blood donors O+ for surgery patient referred from {place} clinic. Transport can be arranged.",
        "Volunteers coordinating rice distribution at {place} — sign-up sheet only, no payment.",
        "Can someone confirm if the {auth} helpline number for {place} shelter is still 108? Asking before forwarding.",
        "Forwarding district advisory about {route} diversion — I have not independently verified, please check radio.",
        "Requesting financial help for rent after flood; no threats, just community support link at {place} desk.",
    ]
    hi = [
        "स्कूल में मदद चाहिए: {place} पर अभिभावक {time} तक बच्चे ले जाएँ — अभ्यास अलर्ट के बाद।",
        "रिश्तेदार की कुशलता जांच: {route} के पास देखा गया, किसी पर आरोप नहीं।",
        "रक्तदाता O+ चाहिए {place} क्लिनिक के मरीज के लिए — परिवहन की व्यवस्था हो सकती है।",
    ]
    hing = [
        "help needed in school — {place} pe parents ko {time} tak pick karna, smoke alarm test tha.",
        "uncle missing welfare check near {route}, kisi ko blame nahi kar rahe.",
        "blood donor O+ chahiye {place} clinic se patient ke liye.",
        "district advisory forward kar raha {route} diversion — khud verify nahi kiya, radio check karo.",
    ]
    return _bundle(GROUP_BENIGN_COORD, "benign_emergency_information", "civ_benign_help", en, hi, hing, 10)


def _report_quote_debunk() -> list[TemplateFamily]:
    report = _bundle(
        GROUP_REPORT_QUOTE,
        "report_or_warning_about_misconduct",
        "civ_rep_warn",
        [
            "Reporting: a man threatened shopkeepers on {route} near {place} — I am telling authorities, not threatening anyone myself.",
            "Scam alert from {auth}: impersonators collecting 'registration fees' at fake desks near {place}.",
            "Please investigate possible fraud — neighbour received demand for bank details for 'school evacuation bus' at {place}.",
        ],
        [
            "रिपोर्ट: {place} के पास {route} पर किसी ने दुकानदारों को धमकाया — मैं अधिकारियों को बता रहा हूँ, खुद धमकी नहीं दे रहा।",
            "{auth} चेतावनी: {place} पर नकली पंजीकरण डेस्क फीस मांग रहे हैं।",
        ],
        [
            "report kar raha — kisi ne shopkeepers ko threaten kiya {route} pe {place} ke paas, main nahi.",
            "scam alert: fake registration desk fee maang rahe {place} pe.",
        ],
        8,
    )
    quoted = _bundle(
        GROUP_REPORT_QUOTE,
        "quoted_or_historical_threat",
        "civ_quote",
        [
            "Witness statement shared with desk: offender said 'pay or I will burn your stock' near {place} — quoting what was reported.",
            "News clip transcript: 'roads will be blocked' shouted during protest on {route}; sharing for context, not a threat from me.",
        ],
        [
            "गवाह ने बताया कि किसी ने कहा 'पैसे दो वरना माल जला देंगे' {place} पर — उद्धरण, मेरी धमकी नहीं।",
        ],
        [
            "witness bola quote: 'pay or burn stock' near {place} — main threaten nahi kar raha, report hai.",
        ],
        8,
    )
    amb = _bundle(
        GROUP_REPORT_QUOTE,
        "ambiguous_needs_review",
        "civ_rep_amb",
        [
            "Unconfirmed post says evacuation bus fee at {place} — might be scam or misunderstanding, can't verify.",
            "Is the curfew message real? Screenshot looks like {auth} but number is odd.",
        ],
        [
            "अपुष्ट: {place} पर बस फीस वाला मैसेज — धोखाधड़ी या गलतफहमी, पक्का नहीं।",
        ],
        [
            "curfew msg real hai kya? screenshot {auth} jaisa but number odd lag raha.",
        ],
        7,
    )
    return report + quoted + amb


def _routine_and_threats() -> list[TemplateFamily]:
    routine = _bundle(
        GROUP_ROUTINE,
        "benign_emergency_information",
        "civ_routine",
        [
            "School timetable update: exams postponed one week; check notice board at {place}.",
            "Hospital OPD queue token system at {place} — average wait {delay} minutes today.",
            "Weather forecast: light rain evening on {route}; carry umbrella, not an evacuation order.",
            "Community meeting Sunday {time} at {place} hall about drainage work — all welcome.",
            "Lost umbrella near {route} bus stop — contact guard at {place}, personal chat.",
        ],
        [
            "स्कूल समय सारणी: परीक्षा एक सप्ताह स्थगित — {place} नोटिस बोर्ड देखें।",
            "मौसम: शाम को हल्की बारिश {route} पर — बस छाता रखें, निकासी आदेश नहीं।",
        ],
        [
            "exam postpone 1 week — notice {place} board pe dekho.",
            "weather: light rain evening {route} pe, evacuation order nahi hai.",
        ],
        7,
    )
    direct = _bundle(
        GROUP_ROUTINE,
        "direct_threat_or_intimidation",
        "civ_threat",
        [
            "If you complain to {auth} about the queue at {place}, you will regret it — keep quiet.",
            "Stay off {route} after dark or we will make sure you cannot reach {place} shelter.",
        ],
        [
            "अगर {place} की कतार की शिकायत की तो परिणाम भुगतोगे — चुप रहो।",
        ],
        [
            "complain kiya toh dekh lenge {place} queue pe — chup raho.",
        ],
        7,
    )
    return routine + direct


def all_civic_families() -> list[TemplateFamily]:
    families: list[TemplateFamily] = []
    families.extend(_traffic_benign())
    families.extend(_traffic_report())
    families.extend(_traffic_fraud_coerce())
    families.extend(_official_benign())
    families.extend(_official_impersonation())
    families.extend(_official_coercion())
    families.extend(_benign_coordination())
    families.extend(_report_quote_debunk())
    families.extend(_routine_and_threats())
    return families
