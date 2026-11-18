// CI for notification-service
// Builds the JAR and the Docker image, pushes it to Docker Hub as
// arafat78003/antier:notification-service-<build number>, then triggers the
// manifest job (main-job.groovy) with that tag.
//
// Jenkins job:  notification-service-ci   (Pipeline script from SCM -> pipeline/notification/main.groovy)
// Credentials:  dockerhub-creds      (Docker Hub username + access token)
pipeline {
    agent any

    options {
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
    }

    environment {
        SERVICE      = 'notification-service'
        GIT_REPO     = 'https://github.com/arafat78003/antier.git'
        GIT_BRANCH   = 'main'
        IMAGE_REPO   = 'arafat78003/antier'
        IMAGE_TAG    = "${SERVICE}-${BUILD_NUMBER}"
        MANIFEST_JOB = 'notification-service-manifest'
    }

    stages {
        stage('Git clone') {
            steps {
                git url: env.GIT_REPO, branch: env.GIT_BRANCH
            }
        }

        stage('Build JAR') {
            steps {
                // Builds only this service plus the parent POM it depends on
                sh 'mvn -B clean package -pl "$SERVICE" -am -DskipTests'
            }
        }

        stage('Docker build') {
            steps {
                sh 'docker build -t "$IMAGE_REPO:$IMAGE_TAG" "$SERVICE"'
            }
        }

        stage('Docker push') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                                                  usernameVariable: 'DOCKER_USER',
                                                  passwordVariable: 'DOCKER_TOKEN')]) {
                    sh '''
                        # Login is kept inside this build's workspace so parallel jobs don't share one
                        export DOCKER_CONFIG="$WORKSPACE/.docker"
                        echo "$DOCKER_TOKEN" | docker login -u "$DOCKER_USER" --password-stdin
                        docker push "$IMAGE_REPO:$IMAGE_TAG"
                    '''
                }
            }
        }

        stage('Trigger manifest update') {
            steps {
                // Waits for the manifest job, so this build fails if the deploy fails
                build job: env.MANIFEST_JOB, parameters: [string(name: 'TAG', value: env.IMAGE_TAG)]
            }
        }
    }

    post {
        always {
            sh '''
                rm -rf "$WORKSPACE/.docker"
                docker rmi "$IMAGE_REPO:$IMAGE_TAG" || true
            '''
        }
    }
}
