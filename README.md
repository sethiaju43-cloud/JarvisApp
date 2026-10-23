# Jarvis (Jarvi)

Android ke liye ek voice assistant. Screen par ek cartoon dost **Jarvi** rehti hai. Aap usse Hindi, Hinglish ya English mein baat kar sakte ho, aur wo phone ke kaam bhi kar deti hai. Jawab Google Gemini se aate hain.

## Kya kar sakti hai
- Floating cartoon jo screen par ghoomti hai, bolti hai aur sunti hai (tap karke bolo)
- Chat screen (likh kar ya bol kar), purani baatein save rehti hain
- Apps kholna, Back, Home, Recents, scroll, click, type
- Call, SMS, WhatsApp message (bhejne se pehle poochti hai, Settings mein band kar sakte ho)
- Alarm, timer, calendar event, torch, volume, battery, notifications, quick settings, screenshot, phone lock
- Photo aur selfie, video fullscreen aur aage-peeche
- Gaana chalana, raasta (Maps), website kholna
- Screen padhkar batana (Accessibility chahiye) aur taaza jaankari ke liye Google Search
- Kai kaam ek saath ("WhatsApp kholo aur Rahul ko hello bhejo")
- Aapke baare mein baatein yaad rakhti hai (Settings se dekh aur hata sakte ho). "Bhool jao" bolo to sab saaf

## APK kaise banaye
1. GitHub par is repo ka **Actions** tab kholo.
2. Sabse naya **Build APK** run kholo.
3. Neeche **Artifacts** mein `Jarvis-APK` download karo, zip kholo, APK install karo.

Computer par banana ho to Android Studio, JDK 17 aur Gradle 8.7 chahiye. Is repo mein Gradle wrapper nahi hai.

## Pehli baar setup
1. App kholo, **Settings** mein apni Gemini API key daalo. Key yahan se milti hai: https://aistudio.google.com/apikey
2. **Permissions** screen se ye allow karo:
   - Accessibility Service: Back, Home, Scroll, Tap, screen padhna
   - Display over other apps: floating Jarvi
   - Microphone: bolna
   - Camera: photo aur selfie
   - Phone & SMS: call aur SMS
   - Contacts: naam se call, SMS, WhatsApp
   - Notifications: background mein chalte rehna
3. Jarvi par tap karo aur bolo. Chalte kaam ko rokne ke liye Jarvi par dobara tap karo ya "ruko" bolo.

## Privacy aur suraksha
- Jab aap screen se judi baat poochte ho, screen ka text (kabhi-kabhi screenshot) Google Gemini ko bheja jaata hai.
- Bank, UPI, message aur password apps ki screen nahi padhi jaati, aur password ke dabbe chhod diye jaate hain.
- API key phone ke Keystore (tijori) mein encrypted rehti hai. App ka backup band hai.
- Chat aur yaaddasht sirf aapke phone mein save hoti hai.
- Gemini ki free limit khatam ho sakti hai, tab Jarvi bolti hai ki thodi der baad try karo.

## Jaanne layak baatein
- Phone restart ke baad (Auto Start ON ho to) Jarvi chalu ho jaati hai, par mic tab tak nahi chalta jab tak aap app ek baar nahi kholte (Android 14 ka rule).
- Screenshot wali cheezein Android 11 ya upar par chalti hain. Kam se kam Android 8.0 chahiye.

## Files ka naksha
| File | Kaam |
|---|---|
| `GeminiClient.kt` | Gemini se baat, yaaddasht, kai-step kaam, rokna |
| `CommandHandler.kt` | Seedhe commands (app kholna, back, home, call, SMS, alarm) |
| `PhoneActions.kt` | Torch, volume, battery, settings, WhatsApp, maps, calendar |
| `JarvisAccessibilityService.kt` | Screen padhna aur tap, scroll, type |
| `FloatingService.kt` | Floating Jarvi, bolna aur sunna |
| `ChatActivity.kt`, `ChatStore.kt` | Chat screen aur uski history |
| `SettingsActivity.kt`, `FactsActivity.kt` | Settings aur "Jarvi ko kya yaad hai" |
| `ApiKeyStore.kt` | API key ka taala |
| `VoiceHelper.kt` | Awaaz ki bhasha aur chunav |
