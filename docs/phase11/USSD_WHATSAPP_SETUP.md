# USSD and WhatsApp: connecting the networks and Meta

Everything below is set in the ops console under **USSD & WhatsApp**. Nothing needs a redeploy.

## USSD (Econet, NetOne, Telecel)

Each network calls PayPort directly in its own format. The menus, payments and wallet login are the same for all three.

| Network | Address to give the network | Format |
|---|---|---|
| Econet | `https://<gateway>/api/v1/ussd/econet?key=<access key>` | Econet messaging XML (`MessageRequest` / `messageResponse`, stage FIRST, PENDING, COMPLETE) |
| NetOne | `https://<gateway>/api/v1/ussd/netone?key=<access key>` | NetOne dynamic menu XML (`USSDDynMenuRequest` / `USSDDynMenuResponse`, rspFlag 1 = continue, 2 = end) |
| Telecel | `https://<gateway>/api/v1/ussd/telecel?key=<access key>` | Telecel JSON (`short_message` "80/74/78 <session> … <input>", reply 72 = continue, 81 = end) |

For each network:

1. Switch the **USSD** channel on (top of the page) and the network on.
2. **Settings**: enter the short code the network gave you (PayPort: `*699#`) and, if the network sends from fixed IP addresses, those addresses.
3. **Create access key** (administrators): the full address with the key is shown once. Give it to the network. A request is accepted when it carries the key **or** comes from an allowed IP address; with neither configured, every request is refused.

What a customer sees when they dial:

- A number with a PayPort account: "Welcome to PayPort, <name>. Enter your wallet PIN, 0. Continue as guest". The PIN is checked once and never stored.
  - Logged in: Buy airtime, Buy data, Pay a bill, Pay a merchant, Send money, Top up my wallet, Cash out at an agent, Balance and statement, Check a payment. The wallet is also offered as a way to pay.
- Any other number (guest): Buy airtime, Buy data, Pay a bill, Pay a merchant, Check a payment.
- Payment methods: EcoCash, OneMoney, O'mari, InnBucks (the PayPort wallet as well once logged in).
  - EcoCash from a non-Econet number and OneMoney from a non-NetOne number ask for the wallet number.
  - O'mari asks for the code O'mari texts the payer, in the same session.
  - InnBucks shows the payment code to approve in the InnBucks app.

Econet and OneMoney are reachable only over their VPNs; the USSD gateways of each network usually are too. Ask each network for their test platform and the IP addresses they call from.

## WhatsApp (Meta WhatsApp Cloud API)

Business number: **+263 71 918 3557** (already set).

From Meta (developers.facebook.com → your app → WhatsApp):

| Field in ops | Where it is in Meta |
|---|---|
| Phone number ID | WhatsApp → API Setup → the number's **Phone number ID** (after adding +263719183557 to the WhatsApp Business Account and verifying it by SMS/voice) |
| WhatsApp Business Account ID | WhatsApp → API Setup → **WhatsApp Business Account ID** |
| Permanent access token | Business Settings → System users → add a system user with the app and the WhatsApp account assigned → **Generate token** with `whatsapp_business_messaging` and `whatsapp_business_management` |
| App secret | App settings → Basic → **App secret** |
| Webhook verify token | Any phrase you choose; type the same phrase in Meta in the next step |

Then in Meta: WhatsApp → Configuration → **Webhook**: Callback URL = the "Callback URL for Meta" shown in ops (`https://<gateway>/api/v1/whatsapp/webhook`), Verify token = your phrase → Verify and save → subscribe to **messages**.

Meta requires an **https** callback URL with a valid certificate: put the gateway behind a domain with TLS (e.g. `api-staging.payport.africa`) before connecting the webhook.

Test: send "hi" to +263 71 918 3557 from a phone, then use **Send test message** in ops (Meta only allows free-form replies within 24 hours of the customer's last message).
