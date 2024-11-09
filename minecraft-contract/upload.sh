simd tx wasm store ./artifacts/minecraft_contract.wasm --from=acc0 --yes --gas-adjustment=2.0 --gas=auto


CODE_ID=`simd q wasm list-code -o=json | jq -r '.code_infos[-1].code_id'`

simd tx wasm instantiate $CODE_ID '{}' --from=acc0 --label="minecraft-ibc" --yes --gas=auto --no-admin --gas-adjustment=1.5
CONTRACT=`simd q wasm list-contract-by-code 1 -o=json | jq -r '.contracts[-1]'` && echo $CONTRACT
