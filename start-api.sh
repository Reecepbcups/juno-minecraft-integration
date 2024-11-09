#!/bin/bash

cd api

# screen -S api

npm i
npm run build

# TODO: move to pm2, but how to do so with .env files hmm
while true; do
  npm run production
done
