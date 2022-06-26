#!/usr/bin/env bash

openapi_json="api/src/main/resources/openapi.json"
new_version=$1
branch_name=$(git rev-parse --abbrev-ref HEAD)

if ! [[ "${new_version}" =~ [0-9]+.[0-9]+.[0-9].* ]]; then
  echo "Invalid version ${new_version}"
  exit 1
fi

if ! [[ "${branch_name}" == 'master' || "${branch_name}" =~ [0-9]+.[0-9]+.x ]]; then
  echo "You are not on the right branch"
  exit 1
fi

if [[ "$OSTYPE" == "linux-gnu"* ]]; then
  sed -E -i 's/"version": "[0-9\.]+"/"version": "'"${new_version}"'"/g' $openapi_json
elif [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' 's/"version": "[0-9\.]+"/"version": "'"${new_version}"'"/g' $openapi_json
else
  echo "Unsupported system $OSTYPE"
  exit 1
fi

git add -A && git commit -m "$(version)"
git tag v$(version)
git push origin v$(version)
git push origin ${branch_name}:${branch_name}
