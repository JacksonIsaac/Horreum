#!/bin/bash

KEYCLOAK_ADMIN_TOKEN=$(curl -s 172.17.0.1:8180/auth/realms/master/protocol/openid-connect/token -X POST -H 'content-type: application/x-www-form-urlencoded' -d 'username=admin&password=secret&grant_type=password&client_id=admin-cli' | jq -r .access_token)
[ -n "$KEYCLOAK_ADMIN_TOKEN" -a "$KEYCLOAK_ADMIN_TOKEN" != "null" ] || exit 1
AUTH='Authorization: Bearer '$KEYCLOAK_ADMIN_TOKEN
KEYCLOAK_BASEURL=172.17.0.1:8180/auth/admin/realms/horreum

# Obtain client secrets for both Horreum and Grafana
HORREUM_CLIENTID=$(curl -s $KEYCLOAK_BASEURL/clients -H "$AUTH" | jq -r '.[] | select(.clientId=="horreum") | .id')
HORREUM_CLIENTSECRET=$(curl -s $KEYCLOAK_BASEURL/clients/$HORREUM_CLIENTID/client-secret -X POST -H "$AUTH" | jq -r '.value')
[ -n "$HORREUM_CLIENTSECRET" -a "$HORREUM_CLIENTSECRET" != "null" ] || exit 1
echo QUARKUS_OIDC_CREDENTIALS_SECRET=$HORREUM_CLIENTSECRET > /etc/horreum/cwd/.env
chmod a+w /etc/horreum/cwd/.env
GRAFANA_CLIENTID=$(curl -s $KEYCLOAK_BASEURL/clients -H "$AUTH" | jq -r '.[] | select(.clientId=="grafana") | .id')
GRAFANA_CLIENTSECRET=$(curl -s $KEYCLOAK_BASEURL/clients/$GRAFANA_CLIENTID/client-secret -X POST -H "$AUTH" | jq -r '.value')
[ -n "$GRAFANA_CLIENTSECRET" -a "$GRAFANA_CLIENTSECRET" != "null" ] || exit 1
echo GF_AUTH_GENERIC_OAUTH_CLIENT_SECRET=$GRAFANA_CLIENTSECRET > /etc/grafana/.grafana
chmod a+w /etc/grafana/.grafana

# Create roles and example user in Keycloak
UPLOADER_ID=$(curl -s $KEYCLOAK_BASEURL/roles/uploader -H "$AUTH"  | jq -r '.id')
TESTER_ID=$(curl -s $KEYCLOAK_BASEURL/roles/tester -H "$AUTH" | jq -r '.id')
VIEWER_ID=$(curl -s $KEYCLOAK_BASEURL/roles/viewer -H "$AUTH" | jq -r '.id')
ADMIN_ID=$(curl -s $KEYCLOAK_BASEURL/roles/admin -H "$AUTH" | jq -r '.id')
curl -s $KEYCLOAK_BASEURL/roles -H "$AUTH" -H 'content-type: application/json' -X POST -d '{"name":"dev-team"}'
TEAM_ID=$(curl -s $KEYCLOAK_BASEURL/roles/dev-team -H "$AUTH" | jq -r '.id')
curl -s $KEYCLOAK_BASEURL/roles -H "$AUTH" -H 'content-type: application/json' -X POST -d '{"name":"dev-uploader","composite":true}'
TEAM_UPLOADER_ID=$(curl -s $KEYCLOAK_BASEURL/roles/dev-uploader -H "$AUTH" | jq -r '.id')
curl -s $KEYCLOAK_BASEURL/roles/dev-uploader/composites -H "$AUTH" -H 'content-type: application/json' -X POST -d '[{"id":"'$TEAM_ID'"},{"id":"'$UPLOADER_ID'"}]'
curl -s $KEYCLOAK_BASEURL/roles -H "$AUTH" -H 'content-type: application/json' -X POST -d '{"name":"dev-tester","composite":true}'
TEAM_TESTER_ID=$(curl -s $KEYCLOAK_BASEURL/roles/dev-tester -H "$AUTH" | jq -r '.id')
curl -s $KEYCLOAK_BASEURL/roles/dev-tester/composites -H "$AUTH" -H 'content-type: application/json' -X POST -d '[{"id":"'$TEAM_ID'"},{"id":"'$TESTER_ID'"},{"id":"'$VIEWER_ID'"}]'
curl -s $KEYCLOAK_BASEURL/users -H "$AUTH" -X POST -d '{"username":"user","enabled":true,"credentials":[{"type":"password","value":"secret"}],"email":"user@example.com"}' -H 'content-type: application/json'
USER_ID=$(curl -s $KEYCLOAK_BASEURL/users -H "$AUTH" | jq -r '.[] | select(.username="user") | .id')
curl -s $KEYCLOAK_BASEURL/users/$USER_ID/role-mappings/realm -H "$AUTH" -H 'content-type: application/json' -X POST -d '[{"id":"'$TEAM_UPLOADER_ID'","name":"dev-uploader"},{"id":"'$TEAM_TESTER_ID'","name":"dev-tester"},{"id":"'$ADMIN_ID'","name":"admin"}]'

# Install datasource in Grafana
GRAFANA_ADMIN_URL=admin:admin@172.17.0.1:4040
while ! curl -s $GRAFANA_ADMIN_URL/api/datasources -H 'content-type: application/json' -d '{"name":"Horreum","type":"simpod-json-datasource","access":"proxy","url":"http://172.17.0.1:8080/api/grafana","basicAuth":false,"withCredentials":false,"isDefault":true,"jsonData":{"oauthPassThru":true},"readOnly":false}'; do sleep 5; done;

# Obtain Grafana API KEY
# First drop key if it already exists
for KEY_ID in $(curl -s $GRAFANA_ADMIN_URL/api/auth/keys | jq .[].id); do curl -s $GRAFANA_ADMIN_URL/api/auth/keys/$KEY_ID -X DELETE; done
GRAFANA_API_KEY=$(curl -s $GRAFANA_ADMIN_URL/api/auth/keys -H 'content-type: application/json' -d '{"name":"Horreum","role":"Editor"}' | jq -r .key)
[ -n "$GRAFANA_API_KEY" -a "$GRAFANA_API_KEY" != "null" ] || exit 1
echo HORREUM_GRAFANA_API_KEY=$GRAFANA_API_KEY >> /etc/horreum/cwd/.env