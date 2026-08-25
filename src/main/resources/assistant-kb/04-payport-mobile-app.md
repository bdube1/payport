# Payport Pay — Mobile App

The Payport Pay mobile app (iOS and Android) gives customers the same
airtime, data, and bill payment experience as the web app, built as a
native app for people who prefer that over a browser.

## Getting started

- Download the Payport app (search "Payport" on the App Store or Google
  Play), or find the download link via app.payport.africa.
- Register with a full name, phone number, and a 4-digit PIN, or log in
  if you already have an account from the web app — accounts work across
  both web and mobile.

## What's in the app

Home, Airtime, Data, Bills, Track Order, transaction processing/status
screens, transaction History, Beneficiaries, Profile, and Help/FAQ —
functionally the same set of services as the web app, since both talk to
the same underlying Payport systems.

## Signing in — PIN and biometrics

- Sign in with your phone number and 4-digit PIN.
- Optionally enable biometric login (Face ID or fingerprint) after your
  first sign-in — this is fully optional and can be turned on or skipped
  each time you're prompted. Biometrics unlock a securely stored PIN,
  which is then used to sign you in fresh each time, rather than just
  bypassing login locally.
- Your PIN and any biometric credentials are stored using your phone's
  own secure hardware storage (Keychain on iOS, encrypted storage on
  Android) — never as plain, readable text.

## If you're asked to change your PIN unexpectedly

If Payport support resets your PIN for you (for example, if you've lost
access to your account), you'll be asked to set a new PIN the next time
you sign in before you can do anything else in the app. This is a
security measure, not an error — follow the prompt to set a new PIN and
you'll continue into the app normally afterward.

## Omari payments on mobile

Omari doesn't use a phone-side approval prompt like EcoCash or OneMoney —
instead, after confirming an Omari payment, you'll be sent a one-time
code by SMS, which you enter directly on the processing screen in the
app. You get up to 3 attempts to enter it correctly, and the code
expires after about 5 minutes.

## Single active device

Payport Pay allows one active login session per account at a time on
mobile. Logging in on a new device signs you out of the previous one
automatically — this is a deliberate security measure, not a bug, if you
find you've been logged out after using the app on another phone.

## Staying signed in

The app keeps you signed in between uses. If you're logged out
unexpectedly, it's most commonly because you (or someone) signed in on a
different device, or because your session genuinely expired after a long
period of inactivity — just sign in again with your phone number and PIN.
