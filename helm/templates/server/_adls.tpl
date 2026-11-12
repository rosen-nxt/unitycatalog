{{- define "unitycatalog.adls.authMode" -}}
{{- $account := required "ADLS storageAccountName is required" .storageAccountName -}}
{{- if not (regexMatch "^[a-zA-Z0-9-]+$" $account) -}}
{{- fail "Invalid ADLS storageAccountName" -}}
{{- end -}}
{{- $mode := "client-secret" -}}
{{- if hasKey . "authMode" -}}
{{- $mode = .authMode -}}
{{- end -}}
{{- if eq $mode "workload-identity" -}}
{{- if or (hasKey . "credentialsSecretName") (hasKey . "tenantId") (hasKey . "clientId") (hasKey . "clientSecret") (hasKey . "credentialGenerator") .testMode -}}
{{- fail "ADLS workload-identity mode cannot use client credentials, test mode or a custom generator" -}}
{{- end -}}
{{- else if eq $mode "client-secret" -}}
{{- $_ := required "ADLS client-secret mode requires credentialsSecretName" .credentialsSecretName -}}
{{- else -}}
{{- fail "Invalid ADLS authentication mode" -}}
{{- end -}}
{{- $mode -}}
{{- end -}}
