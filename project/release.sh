#!/usr/bin/env bash

set -e

openapi_json="api/src/main/resources/openapi.json"
new_version=$1
new_version_without_postfix=$(echo $new_version | cut -d- -f1)
branch_name=$(git rev-parse --abbrev-ref HEAD)

if ! [[ "${new_version}" =~ [0-9]+.[0-9]+.[0-9](-.*)?$ ]]; then
  echo "Invalid version ${new_version}"
  exit 1
fi

if ! [[ "${branch_name}" == 'master' || "${branch_name}" =~ [0-9]+.[0-9]+.x ]]; then
  echo "You are not on the right branch"
  exit 1
fi

if [[ ! -z $(git status --porcelain) ]]; then
  echo "The branch has uncommitted changes"
  exit 1
fi

regex0='s/"version": "[0-9\.]+"/"version": "'"${new_version_without_postfix}"'"/g'
regex1='s/"version": "v[0-9\.]+"/"version": "'"v${new_version_without_postfix}"'"/g'

if [[ "$OSTYPE" == "linux-gnu"* ]]; then
  sed -E -i "${regex0}" $openapi_json
  find ralphc/src/test/resources -name "*.json" -type f -exec sed -E -i "$regex1" {} \;
elif [[ "$OSTYPE" == "darwin"* ]]; then
  sed -E -i '' "$regex0" $openapi_json
  find ralphc/src/test/resources -name "*.json" -type f -exec sed -E -i '' "$regex1" {} \;
else
  echo "Unsupported system $OSTYPE"
  exit 1
fi

git add -A && git commit -m "${new_version}" --allow-empty
git tag v$new_version
git push origin v$new_version
git push origin $branch_name:$branch_name
