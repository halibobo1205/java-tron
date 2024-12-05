FROM amazoncorretto:17.0.13

ENV TMP_DIR="/tron-build"
ENV BASE_DIR="/java-tron"
ENV UNZIP_DISABLE_ZIPBOMB_DETECTION=TRUE


RUN set -o errexit -o nounset \
    && yum -y install git unzip wget \
    && echo "git clone" \
    && mkdir -p $TMP_DIR \
    && cd $TMP_DIR \
    && git clone https://github.com/tronprotocol/java-tron.git \
    && cd java-tron \
    && git checkout master \
    && ./gradlew build -x test \
    && cd build/distributions \
    && unzip -o java-tron-1.0.0.zip  \
    && mv java-tron-1.0.0 $BASE_DIR \
    && rm -rf $TMP_DIR \
    && rm -rf ~/.gradle \
    && yum clean all

RUN wget -P $BASE_DIR/config https://raw.githubusercontent.com/tronprotocol/tron-deployment/master/main_net_config.conf

COPY docker-entrypoint.sh $BASE_DIR/bin

WORKDIR $BASE_DIR

ENTRYPOINT ["./bin/docker-entrypoint.sh"]