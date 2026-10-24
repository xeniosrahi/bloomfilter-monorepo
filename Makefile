.PHONY: all build test clean certs up up-tls down k8s-apply helm-install helm-prod chaos-latency chaos-partition benchmark-gatling benchmark-jmh benchmark-jmeter

all: build

build:
	./gradlew clean build

test:
	./gradlew test

clean:
	./gradlew clean

certs:
	./scripts/generate-tls-certs.sh

up:
	docker compose -f docker-compose.yml up -d --build

up-tls: certs
	docker compose -f docker-compose.tls.yml up -d --build

down:
	docker compose -f docker-compose.yml down -v || true
	docker compose -f docker-compose.tls.yml down -v || true

k8s-apply:
	kubectl apply -k k8s/base

helm-install:
	helm upgrade --install bloom-cluster ./helm/bloomfilter-cluster --namespace staging-environment --create-namespace

helm-prod:
	helm upgrade --install bloom-cluster ./helm/bloomfilter-cluster -f ./helm/bloomfilter-cluster/values-prod.yaml --namespace bloom-production --create-namespace

chaos-latency:
	kubectl apply -f k8s/chaos/chaos-network-latency.yaml

chaos-partition:
	kubectl apply -f k8s/chaos/chaos-network-partition.yaml

benchmark-gatling:
	./gradlew :bloomfilter-benchmarks:gatlingRun -PtargetBaseUrl=http://localhost:8080

benchmark-jmh:
	./gradlew :bloomfilter-benchmarks:jmh

benchmark-jmeter:
	jmeter -n -t bloomfilter-benchmarks/jmeter/cache-penetration-test-plan.jmx -l target/jmeter-results.jtl
