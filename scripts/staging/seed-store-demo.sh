#!/usr/bin/env bash
# Payport Store demo stores for STAGING (owner, 31 Oct 2026): the stores from the owner's front-page design, as real
# merchants with products, so https://store-staging.payport.africa/ shows that page.
#
#   SEED_PASSWORD='<the test password>' scripts/staging/seed-store-demo.sh
#
# Creates each merchant through the normal sign-up, approves it on the STAGING database only, signs in, sets up the
# store and adds the products. Safe to run again (existing merchants and products are kept). Refuses unless both
# database names contain "staging". "Payport Financial Services" already exists: it is only placed in the list.
set -uo pipefail
API=${API:-http://127.0.0.1:8085}
COMMERCE=${COMMERCE:-http://127.0.0.1:8086}
GATEWAY_DB=${GATEWAY_DB:-gateway_staging}
COMMERCE_DB=${COMMERCE_DB:-payport_commerce_staging}
MYSQL=${MYSQL:-mysql}
[ -n "${SEED_PASSWORD:-}" ] || { echo "Set SEED_PASSWORD (the test password for the demo merchants)"; exit 2; }
[[ "$GATEWAY_DB" == *staging* && "$COMMERCE_DB" == *staging* ]] || { echo "REFUSED: database names must contain 'staging'"; exit 2; }
command -v jq >/dev/null || { echo "Install jq first (apt install jq)"; exit 2; }
curl -fsS "$API/actuator/health/readiness" >/dev/null || { echo "The gateway is not answering at $API"; exit 2; }
curl -fsS "$COMMERCE/actuator/health/readiness" >/dev/null || { echo "Payport Commerce is not answering at $COMMERCE"; exit 2; }
sql() { $MYSQL -N -B "$1" -e "$2"; }

# slug | business name | email | phone | type | pay on delivery | featured | rank
STORES="maiskitchen|Mai's Kitchen|maiskitchen@payport.test|+263771100001|FOOD|true|true|1
littlestarskids|Little Stars Kids|littlestarskids@payport.test|+263771100002|CLOTHING|true|true|2
hararebrewco|Harare Brew Co|hararebrewco@payport.test|+263771100003|DRINKS|false|true|3
glowbeautybar|Glow Beauty Bar|glowbeautybar@payport.test|+263771100004|BEAUTY|false|false|11
techhubzim|TechHub Zim|techhubzim@payport.test|+263771100005|ELECTRONICS|false|false|12
sunrisebakery|Sunrise Bakery|sunrisebakery@payport.test|+263771100006|FOOD|true|false|13
zuvafashion|Zuva Fashion|zuvafashion@payport.test|+263771100007|CLOTHING|false|false|14"

# store slug | product | price (cents) | old price (cents, sale) | category | GOODS or SERVICE
PRODUCTS="maiskitchen|Sadza and beef stew|600||Mains|GOODS
maiskitchen|Sadza and chicken|650||Mains|GOODS
maiskitchen|Sadza and road-runner chicken|750||Mains|GOODS
maiskitchen|Rice and chicken|600||Mains|GOODS
maiskitchen|Beef stew (bowl)|500||Mains|GOODS
maiskitchen|Chicken stew (bowl)|500||Mains|GOODS
maiskitchen|Matemba and sadza|450||Mains|GOODS
maiskitchen|Covo|200||Sides|GOODS
maiskitchen|Muriwo une dovi|250||Sides|GOODS
maiskitchen|Chips|250||Sides|GOODS
maiskitchen|Chicken and chips|650||Mains|GOODS
maiskitchen|Boerewors roll|450||Snacks|GOODS
maiskitchen|Mazoe orange 2L|400||Drinks|GOODS
maiskitchen|Coca-Cola 500ml|150||Drinks|GOODS
hararebrewco|Espresso|200||Coffee|GOODS
hararebrewco|Cappuccino|300||Coffee|GOODS
hararebrewco|Cafe latte|320||Coffee|GOODS
hararebrewco|Iced coffee|350|450|Cold drinks|GOODS
hararebrewco|Mango smoothie|400||Cold drinks|GOODS
hararebrewco|Fresh orange juice|350||Cold drinks|GOODS
hararebrewco|Iced tea|250|300|Cold drinks|GOODS
hararebrewco|Hot chocolate|300||Hot drinks|GOODS
hararebrewco|Cold brew 1L|1200||Coffee|GOODS
glowbeautybar|Box braids|2500||Hair|SERVICE
glowbeautybar|Cornrows|1500||Hair|SERVICE
glowbeautybar|Wash and blow-dry|1200||Hair|SERVICE
glowbeautybar|Manicure|1000||Nails|SERVICE
glowbeautybar|Pedicure|1200||Nails|SERVICE
glowbeautybar|Gel nails|1500||Nails|SERVICE
glowbeautybar|Facial|2000||Skin|SERVICE
glowbeautybar|Eyebrow shaping|500||Skin|SERVICE
glowbeautybar|Event make-up|2500||Make-up|SERVICE
glowbeautybar|Shea butter 250g|600||Products|GOODS
glowbeautybar|Hair growth oil|700||Products|GOODS
glowbeautybar|Lip gloss|400||Products|GOODS
glowbeautybar|Body lotion 400ml|800||Products|GOODS
glowbeautybar|Gentle face wash|900||Products|GOODS
glowbeautybar|Nail polish|300||Products|GOODS
glowbeautybar|Braiding hair (pack)|1000||Products|GOODS
glowbeautybar|Eau de parfum 50ml|2500||Products|GOODS
glowbeautybar|Matte lipstick|600||Products|GOODS
techhubzim|Smartphone 64GB|12000||Phones|GOODS
techhubzim|Smartphone 128GB|16500||Phones|GOODS
techhubzim|Feature phone|2500||Phones|GOODS
techhubzim|Tablet 10 inch|18000||Tablets|GOODS
techhubzim|Laptop 14 inch|45000||Laptops|GOODS
techhubzim|Laptop 15.6 inch|52000||Laptops|GOODS
techhubzim|USB-C charger 20W|1200||Chargers|GOODS
techhubzim|Micro-USB charger|600||Chargers|GOODS
techhubzim|Car charger|800||Chargers|GOODS
techhubzim|Power bank 10000mAh|2000||Power|GOODS
techhubzim|Power bank 20000mAh|3200||Power|GOODS
techhubzim|Solar power bank|3500||Power|GOODS
techhubzim|USB-C cable 1m|300||Cables|GOODS
techhubzim|Lightning cable 1m|500||Cables|GOODS
techhubzim|HDMI cable 2m|600||Cables|GOODS
techhubzim|Wireless earbuds|2500||Audio|GOODS
techhubzim|Wired earphones|400||Audio|GOODS
techhubzim|Bluetooth speaker|3000||Audio|GOODS
techhubzim|Phone cover|300||Accessories|GOODS
techhubzim|Screen protector|250||Accessories|GOODS
techhubzim|Memory card 32GB|700||Storage|GOODS
techhubzim|Flash drive 64GB|900||Storage|GOODS
techhubzim|Wireless mouse|1000||Computer|GOODS
techhubzim|Keyboard|1500||Computer|GOODS
techhubzim|Wi-Fi router|4500||Network|GOODS
techhubzim|Smart watch|5000||Wearables|GOODS
sunrisebakery|White bread loaf|120||Bread|GOODS
sunrisebakery|Brown bread loaf|130||Bread|GOODS
sunrisebakery|Bread buns (6)|200||Bread|GOODS
sunrisebakery|Scones (6)|250||Pastries|GOODS
sunrisebakery|Doughnuts (4)|300||Pastries|GOODS
sunrisebakery|Chocolate cake|1500||Cakes|GOODS
sunrisebakery|Birthday cake|2500||Cakes|GOODS
sunrisebakery|Muffins (4)|300||Pastries|GOODS
sunrisebakery|Meat pie|150||Savoury|GOODS
sunrisebakery|Cinnamon rolls (4)|400||Pastries|GOODS
sunrisebakery|Croissant|200||Pastries|GOODS
zuvafashion|Men's shirt|2200||Men|GOODS
zuvafashion|Men's chinos|2800||Men|GOODS
zuvafashion|Men's jeans|3000||Men|GOODS
zuvafashion|Men's polo shirt|1800||Men|GOODS
zuvafashion|Women's maxi dress|3500||Women|GOODS
zuvafashion|Women's blouse|2000||Women|GOODS
zuvafashion|Women's jeans|3000||Women|GOODS
zuvafashion|Women's skirt|2200||Women|GOODS
zuvafashion|Leather sandals|2500||Shoes|GOODS
zuvafashion|Canvas sneakers|2800||Shoes|GOODS
zuvafashion|Wax-print headwrap|800||Accessories|GOODS
zuvafashion|Leather belt|1200||Accessories|GOODS"
# Little Stars Kids: eight items in four sizes (32), some on sale.
for item in "Kids T-shirt|800|1000" "Kids shorts|900|" "Girls' dress|1500|1800" "Kids jeans|1600|" "Kids hoodie|2000|2500" "School shoes|2500|" "Kids sneakers|2200|" "Pyjamas set|1200|"; do
  IFS='|' read -r n p w <<<"$item"
  for size in "2-3 years" "4-5 years" "6-7 years" "8-9 years"; do PRODUCTS="$PRODUCTS
littlestarskids|$n ($size)|$p|$w|Kids|GOODS"; done
done

made=""
while IFS='|' read -r slug name email phone type cod featured rank; do
  echo "== $name ($email)"
  login() { curl -s -X POST "$API/api/v1/merchant-portal/login" -H 'Content-Type: application/json' \
      -d "$(jq -n --arg e "$email" --arg p "$SEED_PASSWORD" '{email:$e,password:$p}')" | jq -r '.data.token // empty'; }
  token=$(login)
  if [ -z "$token" ]; then
    r=$(curl -s -X POST "$API/api/v1/merchant-portal/signup" -H 'Content-Type: application/json' -d "$(jq -n --arg b "$name" --arg e "$email" --arg ph "$phone" \
        --arg p "$SEED_PASSWORD" --arg r "DEMO-$(echo "$slug" | tr a-z A-Z)" '{businessName:$b,registrationNumber:$r,businessCategory:"RETAIL",contactEmail:$e,contactPhone:$ph,
        address:"Harare, Zimbabwe",ownerFullName:($b + " Owner"),password:$p}')")
    id=$(jq -r '.data.id // empty' <<<"$r")
    [ -n "$id" ] || { echo "   sign-up refused: $(jq -r '.error.message // .message // .' <<<"$r")"; continue; }
    sql "$GATEWAY_DB" "UPDATE merchants SET status='ACTIVE' WHERE id=$id;
      INSERT INTO merchant_enablements (merchant_id, country, currency, status, enabled_by, enabled_at)
      SELECT $id, 'ZW', 'USD', 'ENABLED', 'staging-demo', NOW(6) FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM merchant_enablements WHERE merchant_id=$id AND currency='USD');"
    token=$(login)
    [ -n "$token" ] || { echo "   could not sign in after sign-up"; continue; }
    echo "   merchant $id created and approved (staging)"
  fi
  store=$(curl -s -X POST "$COMMERCE/api/commerce/session" -H 'Content-Type: application/json' -d "$(jq -n --arg t "$token" '{portalToken:$t}')" | jq -r '.data.token // empty')
  [ -n "$store" ] || { echo "   the store would not sign in"; continue; }
  auth=(-H "Authorization: Bearer $store" -H 'Content-Type: application/json')
  curl -s -X PUT "$COMMERCE/api/commerce/store" "${auth[@]}" -d "$(jq -n --arg t "$type" --argjson c "$cod" \
      '{businessType:$t,codEnabled:$c,pickupEnabled:true,deliveryEnabled:true,deliveryFeeMinor:200,listed:true,status:"OPEN"}')" | jq -e '.success' >/dev/null \
      || echo "   store settings refused"
  have=$(curl -s "$COMMERCE/api/commerce/products" "${auth[@]}" | jq -r '[.data[]?.name] | join("\n")')
  added=0
  while IFS='|' read -r s pname price was cat kind; do
    [ "$s" = "$slug" ] || continue
    grep -qxF "$pname" <<<"$have" && continue
    body=$(jq -n --arg n "$pname" --argjson p "$price" --arg w "$was" --arg c "$cat" --arg k "$kind" \
      '{name:$n,priceMinor:$p,currency:"USD",category:$c,kind:$k,stock:(if $k=="SERVICE" then null else 50 end)} + (if $w=="" then {} else {compareAtMinor:($w|tonumber)} end) + (if $k=="SERVICE" then {durationMin:60} else {} end)')
    curl -s -X POST "$COMMERCE/api/commerce/products" "${auth[@]}" -d "$body" | jq -e '.success' >/dev/null && added=$((added+1)) || echo "   product refused: $pname"
  done <<<"$PRODUCTS"
  echo "   $added products added"
  sql "$COMMERCE_DB" "UPDATE stores SET directory_featured = $featured, directory_rank = $rank WHERE name = '$(sed "s/'/''/g" <<<"$name")';"
  made="$made
$email"
done <<<"$STORES"

# Payport Financial Services already exists: shown first under All stores.
sql "$COMMERCE_DB" "UPDATE stores SET directory_rank = 10, business_type = 'SERVICES', listed = TRUE WHERE name LIKE 'Payport Financial Services%';"
echo
echo "Done. Demo merchants (password as given in SEED_PASSWORD):$made"
echo "Open $(sed 's#http://127.0.0.1:8086#https://store-staging.payport.africa#' <<<"$COMMERCE")/"
