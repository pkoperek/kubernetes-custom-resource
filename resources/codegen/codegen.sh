docker run \
  --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$(pwd)":"$(pwd)" \
  -ti \
  --network host \
  docker.pkg.github.com/kubernetes-client/java/crd-model-gen:v1.0.3 \
  /generate.sh \
  -u "$(pwd)/crd.yml" \
  -n co.imaginedata.stable \
  -p co.imaginedata.stable \
  -o "$(pwd)"
