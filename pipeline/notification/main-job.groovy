// Manifest update for notification-service
// Writes the new image tag (build number) into kubernetes/apps/notification-service/deployment.yaml
// and pushes it to GitHub. That is all this job does: Argo CD (Application
// piggymetrics-notification-service) sees the new commit and deploys it to the cluster.
// Triggered by notification-service-ci with TAG=notification-service-<build number>.
//
// Jenkins job:  notification-service-manifest   (Pipeline script from SCM -> pipeline/notification/main-job.groovy)
// Credentials:  github-creds               (GitHub username + personal access token with push access)
pipeline {
    agent any

    parameters {
        string(name: 'TAG', defaultValue: '', description: 'Image tag to deploy, e.g. notification-service-12')
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 15, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timestamps()
    }

    environment {
        SERVICE    = 'notification-service'
        GIT_REPO   = 'github.com/arafat78003/antier.git'
        GIT_BRANCH = 'main'
        IMAGE_REPO = 'arafat78003/antier'
        MANIFEST   = "kubernetes/apps/${SERVICE}/deployment.yaml"
    }

    stages {
        stage('Validate tag') {
            steps {
                script {
                    if (!(params.TAG ==~ /${env.SERVICE}-[0-9]+/)) {
                        error("TAG must look like ${env.SERVICE}-<build number>, got '${params.TAG}'")
                    }
                }
            }
        }

        stage('Git clone') {
            steps {
                git url: "https://${env.GIT_REPO}", branch: env.GIT_BRANCH, credentialsId: 'github-creds'
            }
        }

        stage('Modify deployment file') {
            steps {
                sh '''
                    sed -i "s|image: $IMAGE_REPO:$SERVICE-.*|image: $IMAGE_REPO:$TAG|" "$MANIFEST"
                    if ! grep -qF "image: $IMAGE_REPO:$TAG" "$MANIFEST"; then
                        echo "No 'image: $IMAGE_REPO:$SERVICE-...' line found in $MANIFEST"
                        exit 1
                    fi
                    git diff -- "$MANIFEST"
                '''
            }
        }

        stage('Git commit and push') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'github-creds',
                                                  usernameVariable: 'GIT_USER',
                                                  passwordVariable: 'GIT_TOKEN')]) {
                    sh '''
                        if git diff --quiet -- "$MANIFEST"; then
                            echo "$MANIFEST already uses $TAG, nothing to commit."
                            exit 0
                        fi
                        git config user.name  "Jenkins CI"
                        git config user.email "jenkins@piggymetrics.local"
                        git add "$MANIFEST"
                        git commit -m "Deploy $SERVICE $TAG [skip ci]"

                        # Another service's job may have pushed first: rebase and retry
                        REMOTE="https://$GIT_USER:$GIT_TOKEN@$GIT_REPO"
                        for attempt in 1 2 3; do
                            if git pull --rebase "$REMOTE" "$GIT_BRANCH" && git push "$REMOTE" "HEAD:$GIT_BRANCH"; then
                                exit 0
                            fi
                            echo "Push attempt $attempt failed, retrying..."
                            sleep 5
                        done
                        echo "Could not push to $GIT_BRANCH after 3 attempts"
                        exit 1
                    '''
                }
            }
        }
    }
}
