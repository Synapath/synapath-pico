.PHONY: build install verify-sdk
build:
	./scripts/build-apk.sh
install:
	./scripts/install-apk.sh
verify-sdk:
	python3 scripts/verify-sdk.py
